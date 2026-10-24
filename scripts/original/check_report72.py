"""Pure parser/accounting checks; never invokes generators, Java or a model."""
from collections import Counter
import json
from pathlib import Path
import re
import audit72
import report72
from report_accounting import index_usage, cost_summary, split_auxiliary_records


def main():
    java = '''// @Test void fake1() {}
/* @Test void fake2() {} */
class Sample {
 String x = "@Test void fake3() {}";
 String block = """
 @Test void fake4() {}
 """;
 @org.junit.jupiter.api.Test void one() {}
 @Test void two() {}
 @RepeatedTest(3) void repeat() {}
 @ParameterizedTest void param(int n) {}
 @TestFactory Object factory() { return null; }
}
'''
    a, count = audit72.annotations(java)
    masked = report72.LITERALS.sub(lambda x: ' ' * len(x.group()), java)
    b = Counter(re.sub(r'\s+', '', a) for a in report72.ANNOTATION.findall(masked))
    assert a == b
    assert count == 2
    assert sum(v for k, v in b.items() if k.split('.')[-1] == 'Test') == 2
    assert b['ParameterizedTest'] == b['RepeatedTest'] == b['TestFactory'] == 1
    usage = [dict(event='attempt_finished', proxy_instance='selfcheck', attempt_id='a',
        request_id='r', attempt=1, tool='hits', method_id='setup-check',
        label='hits/setup-check', usage=dict(prompt_tokens=10, completion_tokens=3,
        total_tokens=13, prompt_cache_hit_tokens=2))]
    life = [dict(event='attempt_started', proxy_instance='selfcheck', attempt_id='a',
        request_id='r', tool='hits', method_id='setup-check', label='hits/setup-check')]
    mu, ml, au, al, pairs = split_auxiliary_records(usage, life, report72.TOOLS)
    assert not mu and not ml and pairs == {('hits', 'setup-check')}
    by, pending, check = index_usage(au + au, al, pairs)
    cost = cost_summary(by[('hits', 'setup-check')], pending[('hits', 'setup-check')])
    assert not check['issues']
    assert cost['api_attempts'] == 1 and cost['input_tokens'] == 10 and cost['total_tokens'] == 13
    assert cost['cached_tokens'] == 2 and not cost['token_totals_are_observed_lower_bounds']
    unknown = cost_summary([dict(usage={})])
    assert unknown['attempts_without_usage'] == 1 and unknown['token_totals_are_observed_lower_bounds']
    assert audit72.tokens(usage[0]) == (10, 3, 13)
    assert audit72.tokens(dict(usage={})) == (None, None, None)
    value = dict(status='passed', mode='pure_parser_accounting_checks',
        subject_runs=0, model_API_calls=0,
        invariants=['Independent Java lexers ignore comments/literals/text blocks',
            'Plain @Test and parameterized/repeated/factory declarations remain distinct',
            'Source declarations are not assumed equal to discovered JUnit cases',
            'Setup identities partitioned before main sample validation',
            'Duplicate upstream attempts count once',
            'Cache hits included once in input',
            'Missing usage remains a lower bound'],
        files={p.name: report72.sha(p) for p in (Path(report72.__file__),
            Path(audit72.__file__), Path(__file__))})
    report72.dump(Path(__file__).resolve().parent / 'report72-static-validation.json', value)
    print(json.dumps(value, ensure_ascii=False))


if __name__ == '__main__':
    main()
