"""Fail-closed reproduction checks for the declared synthetic controls only.
These are not held-out quality thresholds or a general surface certification.
"""
SUCCESS_CASES={'identical','translated','rotated','permuted','partial','hole','curved_identical','step'}
TOPOLOGY_DEFECTS=('nonmanifoldEdges','nonmanifoldVertices','degenerateFaces','duplicateFaces','windingConflicts','intersections','incomplete')
REFUSALS={
    'complementary':'native coherent-topology guard failed',
    'curved_translated':'ambiguous distinct chart-ray depths',
    'thin_sheets':'ambiguous distinct chart-ray depths',
    'source_budget':'budget:inputTriangles','pair_budget':'budget:candidatePairs',
    'fragment_budget':'budget:cells','vertex_budget':'budget:vertices',
    'face_budget':'budget:faces','contributor_budget':'budget:links',
    'nonfinite':'nonfinite source','same_view_overlap':'same-view chart overlap',
    'duplicate_source_id':'repeated source identity',
    'opposite_oriented_coincident_sheet':'opposite sheet orientation or chart fold',
}
def require(condition,message):
    if not condition:raise AssertionError(message)
def verify_metrics(name,metrics):
    for key in ('lostSupportChartArea','addedUnsupportedChartArea','nonmanifoldEdges','positiveAreaOverlapPairs','summedOverlapChartArea'):
        require(metrics[key]==0,f'{name}: declared support/topology invariant failed: {key}')
    require(metrics['retainedObservations']==metrics['inputObservations'],f'{name}: lost observation records')
    for key in TOPOLOGY_DEFECTS:require(metrics['nativeTopology'][key]==0,f'{name}: native topology defect: {key}')
    require(metrics['nativeTopology']['components']==(2 if name=='step' else 1),f'{name}: unexpected connected components')
def verify_control_report(report):
    expected=SUCCESS_CASES|set(REFUSALS)|{'cancelled','cooperative_precheck'}
    require(set(report)==expected,'Control matrix missing or unexpected cases')
    for name in SUCCESS_CASES:
        record=report[name];require(record['status']=='success',f'{name}: expected declared success, got {record["status"]}')
        require(record['sourceIdsPreserved'] is True,f'{name}: source IDs not preserved')
        verify_metrics(name,record['candidate'])
    for name,reason in REFUSALS.items():
        record=report[name];require(record['status']=='refused',f'{name}: expected declared refusal, got {record["status"]}')
        detail=record.get('candidate',record.get('result',{}))
        require(detail.get('exception')=='Refused' and detail.get('reason','').startswith(reason),f'{name}: wrong failure class/reason')
    for name in ('cancelled','cooperative_precheck'):
        require(report[name]['status']=='cancelled',f'{name}: expected cancellation')
def verify_audit_report(report):
    require(set(report)==SUCCESS_CASES|{'permutation'},'Audit missing or unexpected candidate cases')
    for name in SUCCESS_CASES:
        record=report[name]
        require(record['immutableSourceLedgerExactlyPreserved'] is True,f'{name}: immutable source ledger mismatch')
        require(record['uniqueLedgerIds'] is True,f'{name}: duplicate source ledger IDs')
        verify_metrics(name,record['candidateMetrics'])
        for key in TOPOLOGY_DEFECTS:require(record['nativeTopology'][key]==0,f'{name}: independent native topology defect: {key}')
    require(report['permutation']['triangleCoordinateSetsExactlyEqual'] is True,'Permutation triangle-coordinate sets differ')
