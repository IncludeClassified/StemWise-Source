"""Construct the current workflow in an isolated workspace with its real policy."""
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[3]


def isolate_pipeline_runtime():
    """Keep the launcher's workspace runtime out of disposable pipeline fixtures."""
    import unittest
    from unittest.mock import patch
    environment = patch.dict('os.environ', {'STEMWISE_ALLIN1_PYTHON': ''})
    environment.start()
    unittest.addModuleCleanup(environment.stop)


def nested_case(owner, factory):
    from contextlib import ExitStack
    stack = ExitStack()
    owner.addCleanup(stack.close)
    fixture = factory()
    stack.callback(fixture.doCleanups)
    fixture.setUp()
    return fixture


def initialized_workflow(root):
    from stemwise_engine.future_song import FutureSongWorkflow
    files = ('PC side/stemwise-playlist-builder/config.json',
        'PC side/runtime-layout.json',
        'PC side/stemwise-playlist-builder/future-songs/policy/lyrics-policy.json',
        'PC side/stemwise-playlist-builder/src/common.py',
        'PC side/stemwise-playlist-builder/src/future_song.py',
        'PC side/stemwise-playlist-builder/src/packages.py',
        'StemWise/contracts/enrichment/song-enrichment-v2.schema.json',
        'StemWise/contracts/enrichment/installed-enrichment-v2.schema.json')
    for relative in files:
        target = root/relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT/relative, target)
    return FutureSongWorkflow(root/files[0])
