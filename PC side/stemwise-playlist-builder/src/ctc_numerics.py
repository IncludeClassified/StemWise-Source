"""Bounded full-target CTC recursion shared by current endpoint decoders."""
import numpy as np
from stemwise_engine.acoustic_inputs import CURRENT_BUDGET


def forward_path(emissions, skip):
    CURRENT_BUDGET.require(frames=int(emissions.shape[0]), states=int(emissions.shape[1]), arrays=3)
    alpha = np.full_like(emissions, -np.inf)
    alpha[0, :2] = emissions[0, :2]
    for t in range(1, len(emissions)):
        row = alpha[t-1].copy()
        row[1:] = np.logaddexp(row[1:], alpha[t-1, :-1])
        row[2:] = np.logaddexp(row[2:], np.where(skip, alpha[t-1, :-2], -np.inf))
        alpha[t] = row + emissions[t]
    partition = np.logaddexp(alpha[-1, -1], alpha[-1, -2])
    return alpha, float(partition)
