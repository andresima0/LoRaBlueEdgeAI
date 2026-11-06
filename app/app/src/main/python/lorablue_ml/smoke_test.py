"""Smoke test: checks that the Python runtime and the ML libraries work on-device.

Temporary. Remove it once the real inference module exists.
"""
import json
import pickle
import platform
import time


def run() -> str:
    """Imports every ML dependency, trains a tiny model and round-trips it
    through pickle. Returns a JSON string with the versions and results."""
    started = time.time()

    import joblib
    import numpy as np
    import scipy
    import sklearn
    import threadpoolctl
    from sklearn.ensemble import RandomForestClassifier

    # A tiny, deterministic problem: proves the native code of scikit-learn runs.
    rng = np.random.default_rng(0)
    x = rng.normal(size=(200, 4))
    y = (x[:, 0] + x[:, 1] > 0).astype(int)
    model = RandomForestClassifier(n_estimators=10, random_state=0).fit(x, y)
    accuracy = float(model.score(x, y))

    # The real model will be loaded from a pickle, so test that path as well.
    restored = pickle.loads(pickle.dumps(model))
    roundtrip_ok = bool((restored.predict(x) == model.predict(x)).all())

    return json.dumps({
        "python": platform.python_version(),
        "numpy": np.__version__,
        "scipy": scipy.__version__,
        "scikit-learn": sklearn.__version__,
        "joblib": joblib.__version__,
        "threadpoolctl": threadpoolctl.__version__,
        "train_accuracy": round(accuracy, 3),
        "pickle_roundtrip_ok": roundtrip_ok,
        "elapsed_ms": int((time.time() - started) * 1000),
    }, indent=2)