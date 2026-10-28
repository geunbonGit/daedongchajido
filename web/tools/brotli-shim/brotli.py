"""Minimal stand-in for the `brotli` Python package (only what fontTools' WOFF2 code calls), backed by Node's built-in
zlib brotli. Used by build-fonts.py when `pip install brotli` is not available. Needs `node` on PATH."""
import json
import subprocess

MODE_GENERIC, MODE_TEXT, MODE_FONT = 0, 1, 2

_JS = r"""
const zlib = require('zlib');
const chunks = [];
process.stdin.on('data', c => chunks.push(c));
process.stdin.on('end', () => {
  const input = Buffer.concat(chunks);
  const opt = JSON.parse(process.argv[1]);
  const out = opt.op === 'compress'
    ? zlib.brotliCompressSync(input, { params: {
        [zlib.constants.BROTLI_PARAM_MODE]: opt.mode,
        [zlib.constants.BROTLI_PARAM_QUALITY]: opt.quality,
        [zlib.constants.BROTLI_PARAM_LGWIN]: opt.lgwin,
        [zlib.constants.BROTLI_PARAM_SIZE_HINT]: input.length } })
    : zlib.brotliDecompressSync(input);
  process.stdout.write(out);
});
"""


def _run(data, **opt):
    return subprocess.run(["node", "-e", _JS, json.dumps(opt)], input=bytes(data), capture_output=True, check=True).stdout


def decompress(data):
    return _run(data, op="decompress")


def compress(data, mode=MODE_GENERIC, quality=11, lgwin=22, lgblock=0):
    return _run(data, op="compress", mode=mode, quality=quality, lgwin=lgwin)


class error(Exception):
    pass
