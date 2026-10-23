"""Frozen strict descriptive-metric no-regression gate, not tuned acceptance."""
import math

def compare(base,candidate,topology,ledger):
 failures=[];checks=[]
 def check(name,a,b,direction):
  valid=isinstance(a,(float,int)) and isinstance(b,(float,int)) and math.isfinite(a) and math.isfinite(b)
  passed=valid and (b>=a if direction=='higher' else b<=a);checks.append({'metric':name,'baseline':a,'candidate':b,'direction':direction,'pass':passed})
  if not passed:failures.append(name)
 def cdf(name,a,b):
  if set(a)!=set(b):failures.append(name+':threshold_set');return
  for k in sorted(a):check(name+':'+k,a[k],b[k],'higher')
 def distance(name,a,b):
  for k in ('p50','p95','max'):check(name+':'+k,a.get(k),b.get(k),'lower')
  if b.get('finiteCount')!=b.get('count') or not b.get('count'):failures.append(name+':nonfinite_or_empty')
 if candidate['referenceBoundingBoxDiagonal']!=base['referenceBoundingBoxDiagonal']:failures.append('referenceGauge')
 check('projectedPixelCoverage',base['projectedPixelCoverage'],candidate['projectedPixelCoverage'],'higher')
 if len(base['perView'])!=8 or len(candidate['perView'])!=8:failures.append('allEightViews')
 else:
  for a,b in zip(base['perView'],candidate['perView']):
   name='view:'+str(a['view'])
   if a['view']!=b['view'] or a['observablePixels']!=b['observablePixels']:failures.append(name+':identity')
   check(name+':coverage',a['coverage'],b['coverage'],'higher');cdf(name+':accurateCoverage',a['accurateCoverageCDF'],b['accurateCoverageCDF']);distance(name+':cameraZ',a['absoluteCameraZError'],b['absoluteCameraZError'])
 a=base['referenceSurfaceSampling'];b=candidate['referenceSurfaceSampling']
 for k in ('totalAreaUniformSamples','observableInAtLeastTwoViews','unknownExcludedSamples'):
  if a[k]!=b[k]:failures.append('referenceObservability:'+k)
 cdf('referenceDistanceCDF',a['distanceCDF'],b['distanceCDF']);distance('referenceDistance',a['referenceToCandidateDistance'],b['referenceToCandidateDistance']);cdf('candidateDistanceCDF',base['candidateDistanceCDF'],candidate['candidateDistanceCDF']);distance('candidateDistance',base['candidateToObservableReferenceDistance'],candidate['candidateToObservableReferenceDistance'])
 for k in ('degenerateFaces','duplicateFaces','windingConflictFaces','nonmanifoldEdges','nonmanifoldVertices','intersections','ambiguousPairs'):
  if topology.get(k)!=0:failures.append('topology:'+k)
 if topology.get('intersectionBudgetExceeded') is not False:failures.append('topology:incomplete')
 if not topology.get('faces'):failures.append('emptyGeometry')
 if ledger.get('allPointsRetained')!=4855 or ledger.get('inputHashesUnchanged') is not True or ledger.get('attachedObservations',0)+ledger.get('unattachedObservations',0)!=9171 or ledger.get('unacceptedOriginalSampleIds')!=[4612]:failures.append('immutableLedger')
 return {'status':'EMPIRICAL_GATES_PASS_NOT_PRODUCTION_APPROVAL' if not failures else 'NO_GO','failures':failures,'checks':checks,'policy':'zero topology defects with complete check; exact nonregression all frozen views/CDF thresholds/distance summaries; no tolerance tuning'}
