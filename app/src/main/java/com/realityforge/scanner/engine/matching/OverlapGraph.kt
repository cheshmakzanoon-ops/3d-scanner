package com.realityforge.scanner.engine.matching

import kotlin.math.sqrt

class CandidateGraph internal constructor(
    pairs:List<PairCandidate>,candidateComponents:List<List<String>>,
    duplicateSummaryGroups:List<List<String>>,zeroEvidencePhotos:List<String>,
    val comparisons:Int,val maxNeighbors:Int,
) {
    val pairs:List<PairCandidate> = java.util.Collections.unmodifiableList(ArrayList(pairs))
    val candidateComponents:List<List<String>> = immutableGroups(candidateComponents)
    val duplicateSummaryGroups:List<List<String>> = immutableGroups(duplicateSummaryGroups)
    val zeroEvidencePhotos:List<String> = java.util.Collections.unmodifiableList(ArrayList(zeroEvidencePhotos))
    private fun immutableGroups(groups:List<List<String>>):List<List<String>> = java.util.Collections.unmodifiableList(groups.map{java.util.Collections.unmodifiableList(ArrayList(it))})
}
/** Bounded angular retrieval. No EXIF/file-order proximity is used as geometric evidence.
 * Small collections use exhaustive summary retrieval as a diagnostic reference. Large
 * collections use eight deterministic angular LSH tables with one-bit probing, at most
 * maxComparisonsPerPhoto tested summaries, and strict degree bounds on accepted candidates.
 * This profile is a candidate generator; consumers must measure verified-match recall.
 */
class OverlapGraph(val maxNeighbors:Int=12,val maxComparisonsPerPhoto:Int=96) {
    init {require(maxNeighbors in 1..64 && maxComparisonsPerPhoto in maxNeighbors..512)}
    fun build(input:List<PhotoFeatureSummary>):CandidateGraph {
        require(input.size<=4096 && input.map{it.photoId}.distinct().size==input.size)
        require(input.map{it.dimension}.distinct().size<=1)
        val photos=input.sortedBy{it.photoId}
        val normalized=photos.map { summary->val raw=summary.signature();val norm=sqrt(raw.sumOf{it.toDouble()*it});DoubleArray(raw.size){if(norm>0)raw[it]/norm else 0.0} }
        val zero=photos.indices.filter{normalized[it].all{v->v==0.0}}.toSet()
        val tables=List(8){HashMap<Int,MutableList<Int>>()}
        val keys=Array(photos.size){IntArray(8)}
        for(i in photos.indices)if(i !in zero)for(t in tables.indices){
            val key=hash(normalized[i],t);keys[i][t]=key;tables[t].getOrPut(key){ArrayList()}.add(i)
        }
        var comparisons=0
        val candidates=HashMap<Long,PairCandidate>()
        for(i in photos.indices)if(i !in zero) {
            val indices=linkedSetOf<Int>()
            if(photos.size<=30)photos.indices.filterTo(indices){it!=i && it !in zero}
            else {
                // Probe all exact buckets before their neighbors; rotate deterministic bucket
                // scans by identity so a large bucket cannot always favor its first records.
                for(bit in -1 until 12)for(t in tables.indices) {
                    if(indices.size>=maxComparisonsPerPhoto)break
                    val key=keys[i][t] xor if(bit<0)0 else (1 shl bit)
                    val bucket=tables[t][key]?:continue
                    for(offset in bucket.indices) {
                        val j=bucket[(offset+i)%bucket.size]
                        if(j!=i)indices.add(j)
                        if(indices.size>=maxComparisonsPerPhoto)break
                    }
                }
            }
            val scored=indices.map{j->
                comparisons++
                val cosine=normalized[i].indices.sumOf{normalized[i][it]*normalized[j][it]}.coerceIn(0.0,1.0)
                j to cosine
            }.filter{it.second>0.0}.sortedWith(compareByDescending<Pair<Int,Double>>{it.second}.thenBy{photos[it.first].photoId}).take(maxNeighbors)
            for((j,score) in scored){
                val a=minOf(i,j);val b=maxOf(i,j);val key=(a.toLong() shl 32) or b.toLong()
                candidates[key]=PairCandidate(photos[a].photoId,photos[b].photoId,score,"sift-summary-angular-lsh-v1")
            }
        }
        val byId=photos.withIndex().associate{it.value.photoId to it.index};val degrees=IntArray(photos.size)
        val accepted=candidates.values.sortedWith(compareByDescending<PairCandidate>{it.score}.thenBy{it.firstPhotoId}.thenBy{it.secondPhotoId}).filter{pair->
            val a=byId.getValue(pair.firstPhotoId);val b=byId.getValue(pair.secondPhotoId)
            if(degrees[a]>=maxNeighbors || degrees[b]>=maxNeighbors)false else {degrees[a]++;degrees[b]++;true}
        }
        val adjacency=List(photos.size){ArrayList<Int>()}
        for(p in accepted){val a=byId.getValue(p.firstPhotoId);val b=byId.getValue(p.secondPhotoId);adjacency[a].add(b);adjacency[b].add(a)}
        val visited=BooleanArray(photos.size);val components=ArrayList<List<String>>()
        for(i in photos.indices)if(!visited[i]){
            val queue=java.util.ArrayDeque<Int>();queue.add(i);visited[i]=true;val ids=ArrayList<String>()
            while(queue.isNotEmpty()){val n=queue.removeFirst();ids.add(photos[n].photoId);for(j in adjacency[n])if(!visited[j]){visited[j]=true;queue.add(j)}}
            components.add(ids.sorted())
        }
        val duplicates=photos.indices.filter{it !in zero}.groupBy{normalized[it].toList()}.values.filter{it.size>1}.map{group->group.map{photos[it].photoId}}
        return CandidateGraph(accepted.toList(),components.toList(),duplicates,zero.map{photos[it].photoId},comparisons,maxNeighbors)
    }
    private fun hash(values:DoubleArray,table:Int):Int {
        var key=0
        for(bit in 0 until 12){
            var dot=0.0
            for(i in values.indices){
                var x=(i+1)*0x45d9f3b xor (table+1)*0x119de1f3 xor (bit+1)*0x3449f5
                x=(x xor (x ushr 16))*0x45d9f3b
                dot+=values[i]*((x ushr 1).toDouble()/Int.MAX_VALUE-.5)
            }
            if(dot>=0)key=key or (1 shl bit)
        }
        return key
    }
}
