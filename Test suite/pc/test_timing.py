"""Measured unittest bodies and fixture/runner overhead, printed in the existing run output."""
import json
import time
import unittest

__unittest = True


class TimedTestResult(unittest.TextTestResult):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.timings = []

    def startTest(self, test):
        super().startTest(test)
        self._started = time.perf_counter()
        self._body_seconds = 0.0
        self._original_call = test._callTestMethod
        self._instance_call = test.__dict__.get('_callTestMethod')

        def call(method):
            started = time.perf_counter()
            try:
                return self._original_call(method)
            finally:
                self._body_seconds += time.perf_counter() - started

        test._callTestMethod = call

    def stopTest(self, test):
        elapsed = time.perf_counter() - self._started
        if self._instance_call is None:
            del test._callTestMethod
        else:
            test._callTestMethod = self._instance_call
        self.timings.append({'method': test.id(), 'bodySeconds': self._body_seconds,
                             'fixtureSeconds': max(0.0, elapsed - self._body_seconds)})
        super().stopTest(test)


def run_timed(suite, *, load_seconds=0.0, verbosity=1, resultclass=TimedTestResult, stream=None, failfast=False):
    runner = unittest.TextTestRunner(verbosity=verbosity, resultclass=resultclass, stream=stream, failfast=failfast)
    started = time.perf_counter()
    result = runner.run(suite)
    elapsed = time.perf_counter() - started
    bodies = sum(row['bodySeconds'] for row in result.timings)
    fixtures = sum(row['fixtureSeconds'] for row in result.timings)
    summary = {'loadSeconds': load_seconds, 'runSeconds': elapsed, 'bodySeconds': bodies,
               'testFixtureSeconds': fixtures,
               'suiteFixtureAndRunnerSeconds': max(0.0, elapsed - bodies - fixtures),
               'slowestMethods': sorted(result.timings, key=lambda row: row['bodySeconds'], reverse=True)[:10]}
    runner.stream.writeln('TEST_TIMING ' + json.dumps(summary, sort_keys=True))
    return result
