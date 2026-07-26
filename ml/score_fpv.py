"""FPV generalization test harness  ==  the honest metric.

Feed this a recording of YOUR 5-inch FPV drone (a class the model never saw in
training). It slices the recording into overlapping 1 s windows, scores each with
the public-data-trained baseline model, and reports how many windows are flagged.

Because the model was trained ONLY on Parrot Bebop/Membo camera drones, the
detection rate here tells you how well a generic drone prior transfers to a fast
3-blade FPV racer -- i.e. whether it can detect a drone it has never heard.

    python src/score_fpv.py --audio my_fpv_flight.wav
    python src/score_fpv.py --audio recordings/ --label drone   # a whole folder

Runs on the light stack (numpy/scipy/scikit-learn/soundfile) -- no torch needed.
Record tips: phone mic, a couple of distances (10 m / 30 m / 60 m+), include hover
and throttle sweeps, and grab some drone-OFF ambient too for a false-positive check.
"""
import sys, os, glob, argparse
sys.path.insert(0, os.path.dirname(__file__))
import numpy as np
import joblib
import features as F

AUDIO_EXT = ('.wav', '.flac', '.ogg', '.aif', '.aiff')


def score_file(path, model, thr, hop_sec):
    ts, probs = [], []
    for t, wav in F.windows_from_file(path, hop_sec=hop_sec):
        feat = F.pooled_features(wav).reshape(1, -1)
        probs.append(float(model.predict_proba(feat)[0, 1]))
        ts.append(t)
    probs = np.array(probs)
    flags = probs >= thr
    return ts, probs, flags


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--audio', required=True, help='wav file or a folder of recordings')
    ap.add_argument('--model', default=os.path.join(os.path.dirname(os.path.dirname(__file__)),
                                                    'models', 'baseline.joblib'))
    ap.add_argument('--hop', type=float, default=0.5, help='window hop in seconds')
    ap.add_argument('--label', choices=['drone', 'nodrone'], default=None,
                    help='ground truth, if known, to print recall or false-positive rate')
    a = ap.parse_args()

    bundle = joblib.load(a.model)
    model, thr = bundle['model'], bundle['threshold']
    print(f'model={os.path.basename(a.model)} threshold={thr:.4f}\n')

    if os.path.isdir(a.audio):
        files = sorted(f for f in glob.glob(os.path.join(a.audio, '*')) if f.lower().endswith(AUDIO_EXT))
    else:
        files = [a.audio]
    if not files:
        raise SystemExit(f'No audio found at {a.audio}')

    all_flag, all_n = 0, 0
    for f in files:
        ts, probs, flags = score_file(f, model, thr, a.hop)
        rate = flags.mean() if len(flags) else 0.0
        all_flag += int(flags.sum()); all_n += len(flags)
        peak = probs.max() if len(probs) else 0.0
        print(f'{os.path.basename(f):40s} windows={len(flags):4d} '
              f'flagged={rate*100:5.1f}%  peak_p={peak:.2f}')

    if all_n:
        overall = all_flag / all_n
        print(f'\nOVERALL flagged {all_flag}/{all_n} windows = {overall*100:.1f}%')
        if a.label == 'drone':
            print(f'-> window-level RECALL on your FPV: {overall*100:.1f}% '
                  f'(higher = the generic prior transfers well)')
        elif a.label == 'nodrone':
            print(f'-> window-level FALSE-POSITIVE rate: {overall*100:.1f}% '
                  f'(lower is better; fusion/consensus reduces this further)')
        print('\nNote: even a modest per-window rate is fine -- aggregate over a few '
              'seconds and require N consecutive flags before a node declares a detection.')


if __name__ == '__main__':
    main()
