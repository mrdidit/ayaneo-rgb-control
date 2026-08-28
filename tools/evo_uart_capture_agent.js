'use strict';

/*
 * Passive Pocket EVO UART capture agent.
 *
 * This agent never opens or writes to the UART. It observes libc write/writev
 * calls in the already-running GameWindow process and copies bytes only after
 * the descriptor resolves exactly to /dev/ttyHS4.
 */

const SCHEMA = 'ayaneo-uart-capture/v1';
const TARGET_PATH = '/dev/ttyHS4';
const MAX_CAPTURE_BYTES = 64 * 1024;
const MAX_IOVECS = 1024;
const PATH_BUFFER_BYTES = 4096;
const CLOCK_MONOTONIC = 1;

const suppliedConfig = globalThis.__AYANEO_CAPTURE_CONFIG__ || {};
if (suppliedConfig.target_path !== undefined && suppliedConfig.target_path !== TARGET_PATH) {
  throw new Error(`Refusing non-EVO UART target: ${suppliedConfig.target_path}`);
}

const SESSION_ID = String(suppliedConfig.session_id || 'missing-session-id');
const PROCESS_NAME = String(suppliedConfig.process_name || 'com.ayaneo.gamewindow');
const STREAM_ID = `uart:${TARGET_PATH}`;

const libc = Process.getModuleByName('libc.so');
const readlinkNative = new NativeFunction(
  libc.getExportByName('readlink'),
  'long',
  ['pointer', 'pointer', 'ulong'],
);
const clockGettimeNative = new NativeFunction(
  libc.getExportByName('clock_gettime'),
  'int',
  ['int', 'pointer'],
);

let sequence = 0;
const emittingThreads = new Set();
const listeners = [];

function nextSequence() {
  sequence += 1;
  return sequence;
}

function toSafeUnsignedNumber(value, label) {
  const text = value.toString();
  const number = text.startsWith('0x') || text.startsWith('0X')
    ? parseInt(text.slice(2), 16)
    : Number(text);
  if (!Number.isSafeInteger(number) || number < 0) {
    throw new Error(`${label} is outside JavaScript's safe integer range: ${text}`);
  }
  return number;
}

function readSizeT(address) {
  if (Process.pointerSize === 8) {
    return toSafeUnsignedNumber(address.readU64(), 'size_t');
  }
  return address.readU32();
}

function sizeTArgument(argument) {
  return toSafeUnsignedNumber(argument, 'size_t argument');
}

function signedSsizeResult(retval) {
  if (Process.pointerSize === 8) {
    return new Int64(retval.toString()).toNumber();
  }
  return retval.toInt32();
}

function nativeLongResult(value) {
  if (typeof value === 'number') return value;
  return Number(value.toString());
}

function withPreservedErrno(invocation, operation) {
  if (invocation === null) return operation();
  const savedErrno = invocation.errno;
  try {
    return operation();
  } finally {
    invocation.errno = savedErrno;
  }
}

function monotonicTimestamp(invocation) {
  return withPreservedErrno(invocation, () => {
    const longSize = Process.pointerSize;
    const timespec = Memory.alloc(longSize * 2);
    if (clockGettimeNative(CLOCK_MONOTONIC, timespec) !== 0) return null;

    let seconds;
    let nanoseconds;
    if (longSize === 8) {
      seconds = timespec.readS64().toString();
      nanoseconds = timespec.add(longSize).readS64().toString();
    } else {
      seconds = String(timespec.readS32());
      nanoseconds = String(timespec.add(longSize).readS32());
    }
    return `${seconds}${nanoseconds.padStart(9, '0')}`;
  });
}

function timestampFields(invocation) {
  return withPreservedErrno(invocation, () => ({
    ts_unix_ms: Date.now(),
    ts_mono_ns: monotonicTimestamp(invocation),
  }));
}

function resolveDescriptorPath(fd, invocation) {
  return withPreservedErrno(invocation, () => {
    const procPath = Memory.allocUtf8String(`/proc/self/fd/${fd}`);
    const destination = Memory.alloc(PATH_BUFFER_BYTES);
    const result = nativeLongResult(
      readlinkNative(procPath, destination, PATH_BUFFER_BYTES),
    );
    if (result < 0 || result >= PATH_BUFFER_BYTES) return null;
    return destination.readUtf8String(result);
  });
}

function callerFields(returnAddress) {
  const module = Process.findModuleByAddress(returnAddress);
  if (module === null) {
    return {
      caller_address: returnAddress.toString(),
      caller_module: null,
      caller_offset_hex: null,
    };
  }
  return {
    caller_address: returnAddress.toString(),
    caller_module: module.name,
    caller_offset_hex: returnAddress.sub(module.base).toString(),
  };
}

function baseRecord(kind, invocation) {
  return {
    schema: SCHEMA,
    kind,
    session_id: SESSION_ID,
    seq: nextSequence(),
    ...timestampFields(invocation),
    pid: Process.id,
    tid: invocation === null ? Process.getCurrentThreadId() : invocation.threadId,
    process: PROCESS_NAME,
  };
}

function emit(record, data, invocation) {
  const tid = invocation === null ? Process.getCurrentThreadId() : invocation.threadId;
  if (emittingThreads.has(tid)) return false;

  const savedErrno = invocation === null ? null : invocation.errno;
  emittingThreads.add(tid);
  try {
    if (data === null || data === undefined) {
      send(record);
    } else {
      send(record, data);
    }
    return true;
  } finally {
    emittingThreads.delete(tid);
    if (invocation !== null) invocation.errno = savedErrno;
  }
}

function appendCaptureError(existing, error) {
  const message = error instanceof Error ? error.message : String(error);
  return existing === null ? message : `${existing}; ${message}`;
}

function captureWrite(buffer, requested) {
  const captureLength = Math.min(requested, MAX_CAPTURE_BYTES);
  if (captureLength === 0) {
    return {
      requested,
      captured: 0,
      truncated: requested > 0,
      data: null,
      capture_error: null,
    };
  }

  try {
    const data = buffer.readByteArray(captureLength);
    return {
      requested,
      captured: captureLength,
      truncated: captureLength < requested,
      data,
      capture_error: null,
    };
  } catch (error) {
    return {
      requested,
      captured: 0,
      truncated: true,
      data: null,
      capture_error: appendCaptureError(null, error),
    };
  }
}

function captureWritev(iov, iovcnt) {
  const iovecs = [];
  const chunks = [];
  let requested = 0;
  let captured = 0;
  let captureError = null;
  let requestComplete = true;

  if (iovcnt < 0 || iovcnt > MAX_IOVECS) {
    return {
      requested: 0,
      captured: 0,
      truncated: true,
      data: null,
      iovecs,
      request_complete: false,
      capture_error: `iovcnt ${iovcnt} is outside 0..${MAX_IOVECS}`,
    };
  }

  const stride = Process.pointerSize * 2;
  for (let index = 0; index < iovcnt; index += 1) {
    try {
      const entry = iov.add(index * stride);
      const base = entry.readPointer();
      const length = readSizeT(entry.add(Process.pointerSize));
      if (!Number.isSafeInteger(requested + length)) {
        throw new Error(`aggregate iovec length is outside JavaScript's safe integer range`);
      }

      const offset = captured;
      const captureLength = Math.min(length, MAX_CAPTURE_BYTES - captured);
      if (captureLength > 0) {
        const chunk = base.readByteArray(captureLength);
        chunks.push({ offset, data: chunk });
        captured += captureLength;
      }
      requested += length;
      iovecs.push({
        index,
        requested: length,
        captured: captureLength,
        offset,
      });
    } catch (error) {
      captureError = appendCaptureError(captureError, `iov[${index}]: ${error}`);
      requestComplete = false;
      break;
    }
  }

  let data = null;
  if (captured > 0) {
    const combined = new Uint8Array(captured);
    for (const chunk of chunks) {
      combined.set(new Uint8Array(chunk.data), chunk.offset);
    }
    data = combined.buffer;
  }

  return {
    requested,
    captured,
    truncated: !requestComplete || captured < requested,
    data,
    iovecs,
    request_complete: requestComplete,
    capture_error: captureError,
  };
}

function prepareInvocation(invocation, fd, op) {
  invocation.ayaneoCapture = null;
  if (emittingThreads.has(invocation.threadId) || fd < 0) return null;

  const fdPath = resolveDescriptorPath(fd, invocation);
  if (fdPath !== TARGET_PATH) return null;

  const record = {
    ...baseRecord('write', invocation),
    stream_id: STREAM_ID,
    writer_id: `${Process.id}:${fd}`,
    fd,
    fd_path: fdPath,
    op,
    ...callerFields(invocation.returnAddress),
  };
  invocation.ayaneoCapture = { record, data: null };
  return invocation.ayaneoCapture;
}

function finishInvocation(invocation, retval) {
  const capture = invocation.ayaneoCapture;
  if (capture === null || capture === undefined) return;

  const syscallErrno = invocation.errno;
  const result = signedSsizeResult(retval);
  capture.record.result = result;
  capture.record.errno = result < 0 ? syscallErrno : null;
  emit(capture.record, capture.data, invocation);
  invocation.errno = syscallErrno;
}

function hookWrite() {
  const address = libc.getExportByName('write');
  const listener = Interceptor.attach(address, {
    onEnter(args) {
      const capture = prepareInvocation(this, args[0].toInt32(), 'write');
      if (capture === null) return;

      let snapshot;
      try {
        snapshot = captureWrite(args[1], sizeTArgument(args[2]));
      } catch (error) {
        snapshot = {
          requested: null,
          captured: 0,
          truncated: true,
          data: null,
          capture_error: appendCaptureError(null, error),
        };
      }
      capture.record.requested = snapshot.requested;
      capture.record.captured = snapshot.captured;
      capture.record.truncated = snapshot.truncated;
      if (snapshot.capture_error !== null) {
        capture.record.capture_error = snapshot.capture_error;
      }
      capture.data = snapshot.data;
    },
    onLeave(retval) {
      finishInvocation(this, retval);
    },
  });
  listeners.push(listener);
  return { op: 'write', address: address.toString() };
}

function hookWritev() {
  const address = libc.getExportByName('writev');
  const listener = Interceptor.attach(address, {
    onEnter(args) {
      const capture = prepareInvocation(this, args[0].toInt32(), 'writev');
      if (capture === null) return;

      const snapshot = captureWritev(args[1], args[2].toInt32());
      capture.record.requested = snapshot.requested;
      capture.record.captured = snapshot.captured;
      capture.record.truncated = snapshot.truncated;
      capture.record.request_complete = snapshot.request_complete;
      capture.record.iovecs = snapshot.iovecs;
      if (snapshot.capture_error !== null) {
        capture.record.capture_error = snapshot.capture_error;
      }
      capture.data = snapshot.data;
    },
    onLeave(retval) {
      finishInvocation(this, retval);
    },
  });
  listeners.push(listener);
  return { op: 'writev', address: address.toString() };
}

let installedHooks;
try {
  installedHooks = [hookWrite(), hookWritev()];
} catch (error) {
  for (const listener of listeners) listener.detach();
  emit(
    {
      ...baseRecord('error', null),
      phase: 'agent_init',
      message: String(error),
    },
    null,
    null,
  );
  throw error;
}

rpc.exports = {
  mark(label) {
    const cleaned = String(label).trim();
    if (cleaned.length === 0) throw new Error('Marker label must not be empty');
    if (cleaned.length > 200) throw new Error('Marker label exceeds 200 characters');
    const record = {
      ...baseRecord('marker', null),
      label: cleaned,
      target_path: TARGET_PATH,
      stream_id: STREAM_ID,
    };
    emit(record, null, null);
    return record.seq;
  },
  status() {
    return {
      schema: SCHEMA,
      session_id: SESSION_ID,
      pid: Process.id,
      process: PROCESS_NAME,
      target_path: TARGET_PATH,
      stream_id: STREAM_ID,
      hooks: installedHooks,
    };
  },
};

emit(
  {
    ...baseRecord('session', null),
    phase: 'agent_started',
    target_path: TARGET_PATH,
    stream_id: STREAM_ID,
    max_capture_bytes: MAX_CAPTURE_BYTES,
    architecture: Process.arch,
    pointer_size: Process.pointerSize,
    hooks: installedHooks,
  },
  null,
  null,
);
