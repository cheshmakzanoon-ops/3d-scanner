"""Exercise production validation AST under real normal, -O and -OO interpreters.

This deliberately isolates the acceptance checks from expensive reconstruction and
OpenCV. It is not a native, real-image, or Android execution qualification.
"""
from pathlib import Path
import subprocess
import sys
import textwrap

import pytest

ROOT = Path(__file__).resolve().parents[1]

CHECKS = r'''
import argparse, ast, contextlib, io, json, math, pathlib, sys, tempfile
import numpy as np
root = pathlib.Path(sys.argv[1])
paths = ['benchmark_surface_middlebury.py', 'benchmark_stereo_kernel.py',
         'check_sparse_reverse_matching.py']
trees = {name: ast.parse((root/'tools'/name).read_text()) for name in paths}
for name, tree in trees.items():
    if any(isinstance(node, ast.Assert) for node in ast.walk(tree)):
        raise RuntimeError('Optimization-removable validation in '+name)
checks = 0

def check(name, predicate, cases):
    global checks
    target = ast.dump(ast.parse('not ('+predicate+')', mode='eval').body)
    found = [n for n in ast.walk(trees[name]) if isinstance(n, ast.If)
             and ast.dump(n.test) == target]
    if len(found) != 1:
        raise RuntimeError('Missing or ambiguous guard: '+predicate)
    statement = found[0]
    code = compile(ast.fix_missing_locations(ast.Module(body=[statement], type_ignores=[])),
                   str(root/'tools'/name), 'exec')
    for scope, passes, expected_args in cases:
        scope = {'np': np, **scope}
        try:
            exec(code, scope)
        except AssertionError as error:
            if passes or error.args != expected_args:
                raise RuntimeError('Unexpected rejection or diagnostic: '+predicate) from error
        else:
            if not passes:
                raise RuntimeError('Invalid input accepted: '+predicate)
        checks += 1

surface = paths[0]
check(surface, 'len(vertices)>0 and len(triangles)>0', [
    ({'vertices': v, 'triangles': t}, bool(v and t), ())
    for v, t in [([], []), ([0], []), ([], [0]), ([0], [0])]])
check(surface, 'np.all((triangles>=0)&(triangles<len(vertices)))', [
    ({'vertices': [0, 1, 2], 'triangles': np.asarray(t, dtype=np.int64)}, valid, ())
    for t, valid in [([0,1,2], True), ([-1,0,1], False), ([0,1,3], False),
                     ([-3,1,2], False), ([], True)]])
limits = [('meshTriangleCount', '>=100', [100,101,99,float('nan')]),
          ('meshVertexCount', '>=80', [80,81,79,float('nan')]),
          ('groundTruthMatchedMeshVertices', '>=50', [50,51,49,float('nan')]),
          ('disparityAbsoluteErrorPxP50', '<5', [4.9,0,5,float('nan')]),
          ('depthRelativeErrorP50', '<.20', [.19,0,.20,float('nan')]),
          ('maximumFaceRelativeDepthJump', '<=.081', [.081,0,.082,float('nan')])]
for key, comparison, values in limits:
    check(surface, "result['"+key+"']"+comparison,
          [({'result': {key: value}}, index < 2, ()) for index,value in enumerate(values)])
check(paths[1], 'old is not None and new is not None', [
    ({'old': a, 'new': b}, a is not None and b is not None, ())
    for a,b in [(None,None), ({},None), (None,{}), ({},{})]])
for old, baseline, new, valid in [([1],[1],[1],True), ([1],[2],[1],False),
                                  ([1],[1],[2],False)]:
    scope = dict(old=old, baseline=baseline, new=new, name='fixture', block=17)
    check(paths[2], 'old==baseline==new', [(scope, valid,
          () if valid else (('fixture',17,old,new),))])
check(paths[2], 'old==new', [({'old': old,'new': new},valid,())
    for old,new,valid in [([],[],True), ([1],[1],True), ([1],[2],False)]])

# Execute the real CLI function with a synthetic result, not a copied gate.
main_node = next(n for n in trees[surface].body if isinstance(n,ast.FunctionDef) and n.name=='main')
namespace = {'argparse': argparse, 'Path': pathlib.Path, 'json': json, '__doc__': 'synthetic test'}
exec(compile(ast.fix_missing_locations(ast.Module(body=[main_node],type_ignores=[])),
             str(root/'tools'/surface),'exec'),namespace)
valid_result = {key: values[0] for key,_,values in limits}
with tempfile.TemporaryDirectory() as directory:
    output = pathlib.Path(directory)/'result.json'
    for key, _, values in limits:
        for bad in values[2:]:
            result = {**valid_result, key: bad}
            namespace['run'] = lambda result=result: result
            for gated in (False,True):
                sys.argv = ['benchmark', '--output', str(output)] + (['--gate'] if gated else [])
                rejected = False
                try:
                    with contextlib.redirect_stdout(io.StringIO()): namespace['main']()
                except AssertionError:
                    rejected = True
                if rejected != gated or not output.is_file():
                    raise RuntimeError('CLI gate behavior changed for '+key)
                checks += 1
print(json.dumps({'checks': checks, 'optimization': sys.flags.optimize}))
'''


@pytest.mark.parametrize('flag,level', [('', 0), ('-O', 1), ('-OO', 2)])
def test_validation_survives_optimized_python(flag, level):
    command = [sys.executable] + ([flag] if flag else [])
    completed = subprocess.run(command + ['-c', textwrap.dedent(CHECKS), str(ROOT)],
                               capture_output=True, text=True, timeout=30)
    assert completed.returncode == 0, completed.stdout + completed.stderr
    import json
    receipt = json.loads(completed.stdout)
    assert receipt == {'checks': 67, 'optimization': level}
