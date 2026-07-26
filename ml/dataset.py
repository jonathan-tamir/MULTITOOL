"""Build the file list + leakage-aware group keys from the Al-Emadi DADS layout.

Why group keys matter: the dataset was augmented, so many clips share a single
source recording. A naive random split would put augmented siblings in both
train and test and massively inflate the score. We assign every clip a group id
so GroupShuffleSplit can keep a whole source recording on one side of the split.
"""
import glob, os, re

DEFAULT_ROOT = os.environ.get(
    'DRONE_DATA',
    os.path.join(os.path.dirname(os.path.dirname(__file__)), 'data', 'DroneAudioDataset'),
)


def _drone_group(fname):
    # Membo_1_004-membo_000_.wav -> "Membo_1_004"; B_S2_D1_094-bebop_003_.wav -> "B_S2_D1"
    head = os.path.basename(fname).split('-')[0]
    return '_'.join(head.split('_')[:3])


def _neg_group(fname):
    # ESC-50 style "3-148330-A-212.wav" -> "neg_148330" (keep augment variants together)
    m = re.match(r'\d+-(\d+)', os.path.basename(fname))
    return 'neg_' + (m.group(1) if m else os.path.basename(fname))


def build_index(root=DEFAULT_ROOT, neg_cap=4500, seed=0):
    """Returns (files, labels, groups). neg_cap balances the ~1:8 class ratio
    and keeps feature extraction fast; raise it to use all negatives."""
    import random
    rng = random.Random(seed)
    pos = sorted(glob.glob(os.path.join(root, 'Binary_Drone_Audio', 'yes_drone', '*.wav')))
    neg = sorted(glob.glob(os.path.join(root, 'Binary_Drone_Audio', 'unknown', '*.wav')))
    if not pos:
        raise SystemExit(f'No drone clips found under {root}. Set DRONE_DATA or pass --data.')
    rng.shuffle(neg)
    if neg_cap:
        neg = neg[:neg_cap]
    files  = pos + neg
    labels = [1] * len(pos) + [0] * len(neg)
    groups = [_drone_group(f) for f in pos] + [_neg_group(f) for f in neg]
    return files, labels, groups
