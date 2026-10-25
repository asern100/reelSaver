"""Music-removal CI check.
  make  <speech.ogg> <music.ogg> <outdir> : builds speech+music test video (0 dB) and the clean speech reference
  score <outdir> <result.mp4>             : how much closer to the clean speech the app's output is (SDR, dB)
"""
import numpy as np, subprocess, sys

SR = 44100

def decode(path, extra=()):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, *extra, "-vn", "-ac", "2", "-ar", str(SR),
                          "-f", "f32le", "-"], check=True, capture_output=True).stdout
    return np.frombuffer(raw, np.float32).reshape(-1, 2).T.astype(np.float64)

def sdr(ref, est):
    """SDR (dB) of [est] against [ref] after lining them up (codecs add a small delay)"""
    n = min(ref.shape[1], est.shape[1])
    ref, est = ref[:, :n], est[:, :n]
    m = 1 << (2 * n - 1).bit_length()
    xc = np.fft.irfft(np.fft.rfft(est[0], m) * np.conj(np.fft.rfft(ref[0], m)), m)
    lags = np.r_[0:4097, -4096:0]
    lag = int(lags[np.argmax(np.abs(xc[lags]))])
    if lag >= 0: r, e = ref[:, :n - lag], est[:, lag:]
    else: r, e = ref[:, -lag:], est[:, :n + lag]
    return 10 * np.log10((r ** 2).sum() / ((r - e) ** 2).sum())

if sys.argv[1] == "make":
    sp, mu, out = decode(sys.argv[2]), decode(sys.argv[3]), sys.argv[4]
    n = min(sp.shape[1], mu.shape[1]); sp, mu = sp[:, :n], mu[:, :n]
    mu *= np.sqrt((sp ** 2).mean() / (mu ** 2).mean())
    sp.T.astype(np.float32).tofile(f"{out}/speech.f32")
    (sp + mu).T.astype(np.float32).tofile(f"{out}/mix.f32")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"color=c=0x1E8E5A:s=360x640:d={n / SR:.2f}",
                    "-f", "f32le", "-ar", str(SR), "-ac", "2", "-i", f"{out}/mix.f32",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "-shortest",
                    f"{out}/nm_test.mp4"], check=True)
    print(f"test clip: {n / SR:.1f}s")
else:
    out, res = sys.argv[2], sys.argv[3]
    sp = np.fromfile(f"{out}/speech.f32", np.float32).reshape(-1, 2).T.astype(np.float64)
    before, after = sdr(sp, decode(f"{out}/nm_test.mp4")), sdr(sp, decode(res))
    print(f"{before:.1f} {after:.1f}")
