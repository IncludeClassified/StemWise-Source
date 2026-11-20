"""Shared HTDemucs CPU worker with native hard per-song deadlines."""
import argparse
from pathlib import Path
from stemwise_engine.private_diagnostics import retain_demix_failure
from stemwise_engine.allin1_worker import publish_active


def run_demix(request_path: Path, output: Path):
    import json, signal, sys, time
    from pathlib import Path
    request = json.loads(Path(request_path).read_text(encoding='utf-8'))
    signal.signal(signal.SIGALRM, signal.SIG_DFL)
    inherited = request.get('spentSeconds', {})
    setup_budget = (request['timeoutSeconds'] - (time.monotonic()-request['batchStartedMonotonic'])
        - min(float(inherited.get(str(item['index']), 0.)) for item in request['inputs']))
    if setup_budget <= 0:
        raise TimeoutError('All-In-One demix setup deadline expired')
    signal.setitimer(signal.ITIMER_REAL, setup_budget)
    from demucs import separate

    out = Path(output)
    separator = None
    original = separate.Separator

    def shared(**kwargs):
        nonlocal separator
        if separator is None:
            separator = original(**kwargs)
        return separator
    separate.Separator = shared
    setup_seconds = max(0., time.monotonic()-request['batchStartedMonotonic']-request['setupSeconds'])
    signal.setitimer(signal.ITIMER_REAL, 0)
    spent = {**{str(item['index']): 0. for item in request['inputs']}, **request.get('spentSeconds', {})}
    for item in request['inputs']:
        started = time.monotonic()
        publish_active(Path(request_path).parent, request['requestId'], item['index'], {
            index: float(seconds) + request['setupSeconds'] + setup_seconds for index, seconds in spent.items()})
        try:
            budget = request['timeoutSeconds'] - request['setupSeconds'] - setup_seconds - spent.get(str(item['index']), 0.)
            if budget <= 0:
                raise TimeoutError('All-In-One demix setup deadline expired')
            signal.setitimer(signal.ITIMER_REAL, budget)
            separate.main(['--out', str(out), '--name', 'htdemucs', '--device', 'cpu', item['audio']])
            row = {'ok': True}
        except Exception as error:
            row = {'ok': False, 'error': type(error).__name__}
            retain_demix_failure(error, Path(request_path).parent, item)
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)
        row['seconds'] = time.monotonic() - started
        row.update(requestId=request['requestId'], **item)
        spent[str(item['index'])] = spent.get(str(item['index']), 0.) + row['seconds']
        Path(request_path).parent.joinpath('demix-' + str(item['index']) + '.json').write_text(json.dumps(row), encoding='utf-8')


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('request', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args(argv)
    run_demix(args.request, args.output)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
