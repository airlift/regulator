"""Reproducible uncertainty analysis for paired hosts and independent JVM starts.

Requires NumPy. Resample hosts jointly for both engines, then complete JVM
forks separately within each selected host. Never resample individual JMH
iterations as independent measurements. These approximate percentile intervals
cannot characterize unobserved host or compiler states from only three hosts.
"""
import hashlib
import math
import numpy as np

METHOD='hierarchical-bootstrap-v1'

def uncertainty(hosts, estimator, identity, replicates=4000):
    if estimator not in ('mean','median') or len(hosts)<3 or len({h['instanceId'] for h in hosts})!=len(hosts):
        raise ValueError('uncertainty requires a recognized estimator and independent hosts')
    rng=np.random.default_rng(int.from_bytes(hashlib.sha256(identity.encode()).digest()[:8],'big'))
    n=len(hosts)
    draws=rng.integers(n,size=(replicates,n))
    costs={};ranges={};processes={};conditional=[];components={};aggregates=[]
    for side in ('candidate','comparator'):
        sampled=np.empty((replicates,n));all_means=[];counts=[];within=[];between=[];host_means=[]
        for host_index,host in enumerate(hosts):
            source=host[side]
            groups=source.get('groups')
            if groups is None and 'processMeansNs' in source:
                values=source['processMeansNs'] if estimator=='mean' else source['processMediansNs']
                groups=[[v] for v in values]
                aggregates.append({'instanceId':host['instanceId'],'engine':side,'kind':'recorded-native-process-aggregates'})
            if groups is None:
                # Preserve a measured host aggregate; it is not a fabricated raw sample.
                point=source['meanNs' if estimator=='mean' else 'medianNs']
                if not math.isfinite(point) or point<=0:raise ValueError('invalid measured host aggregate')
                sampled[draws==host_index]=point;host_means.append(point);counts.append(None)
                conditional.append({'instanceId':host['instanceId'],'engine':side})
                continue
            raw=np.asarray(groups,dtype=float)
            if raw.ndim!=2 or 0 in raw.shape or not np.all(np.isfinite(raw)&(raw>0)):
                raise ValueError('incomplete raw fork matrix')
            forks=raw.shape[0];means=raw.mean(axis=1)
            all_means.extend(means.tolist());counts.append(forks);host_means.append(float(means.mean()))
            if raw.shape[1]>1:within.extend((raw.std(axis=1,ddof=1)/means).tolist())
            if forks>1:between.append(float(means.std(ddof=1)/means.mean()))
            positions=np.where(draws==host_index)
            choices=rng.integers(forks,size=(len(positions[0]),forks))
            if estimator=='mean':values=means[choices].mean(axis=1)
            else:values=np.median(raw[choices].reshape(len(choices),-1),axis=1)
            sampled[positions]=values
        costs[side]=sampled.mean(axis=1) if estimator=='mean' else np.median(sampled,axis=1)
        ranges[side]=[min(all_means),max(all_means)] if all_means else None
        processes[side]=counts
        components[side]={'medianWithinForkCv':float(np.median(within)) if within else None,
                          'medianBetweenForkCv':float(np.median(between)) if between else None,
                          'betweenHostMeanCv':float(np.std(host_means,ddof=1)/np.mean(host_means))}
        if side=='candidate':candidate_draws=sampled.copy()
        else:comparator_draws=sampled
    ratios=costs['candidate']/costs['comparator'] if estimator=='mean' else np.median(candidate_draws/comparator_draws,axis=1)
    interval=lambda values:np.quantile(values,[.025,.975]).tolist()
    return {'method':METHOD,'level':.95,'replicates':replicates,'hostCount':n,
            'ratioInterval':interval(ratios),'candidateIntervalNs':interval(costs['candidate']),
            'comparatorIntervalNs':interval(costs['comparator']),'forkMeanRangeNs':ranges,
            'processCounts':processes,'conditionalHostAggregates':conditional,'aggregateProcessInputs':aggregates,'components':components}
