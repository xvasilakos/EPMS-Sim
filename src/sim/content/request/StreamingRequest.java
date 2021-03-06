package sim.content.request;

import caching.base.AbstractCachingModel;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import sim.content.Chunk;
import sim.space.users.CachingUser;
import traces.dmdtrace.TraceWorkloadRecord;

/**
 * @author xvas
 */
public class StreamingRequest extends DocumentRequest {

    public StreamingRequest(TraceWorkloadRecord workloadRecord, CachingUser requesterUser) {
        super(workloadRecord, requesterUser);
    }

    @Override
    public void consumeTry(
            double mcRateSlice, Map<AbstractCachingModel, List<Chunk>> fillInWithDownloadedFromMC,
            double scRateSlice, Map<AbstractCachingModel, List<Chunk>> fillInWithCacheHits,
            double minSCorBHRateSlice, Map<AbstractCachingModel, List<Chunk>> fillInWithDownloadedFromBH,
            Map<AbstractCachingModel, List<Chunk>> fillInWithMissedPerPolicy) {

        long chunkSizeInBytes = getSimulation().chunkSizeInBytes();

        boolean userConnected = getRequesterUser().isConnected();

        long maxBudget;

        if (!userConnected) {
            maxBudget = Math.round(mcRateSlice / chunkSizeInBytes);
            for (AbstractCachingModel policy : getSimulation().getCachingStrategies()) {
                mergeToFirstMap(fillInWithDownloadedFromMC, super.consumeFromMCwSCDiscon(policy, maxBudget));
            }
        } else {// in this case, downloads from all reasources, with this *priority*: 
            for (AbstractCachingModel policy : getSimulation().getCachingStrategies()) {
// CAUTION! do not change  the priority of the following invokations!    

                if (policy != caching.incremental.Oracle.instance()) {
                    // cached chunks are fully consumed at caching decision time  
                    // (See the caching method for Oracle..)
                    //since it is oracle.
                    
// First, consumeTry from the cache
                    maxBudget = Math.round(scRateSlice / chunkSizeInBytes);
                    Map<AbstractCachingModel, List<Chunk>> hitsInCachePerPolicy
                            = tryStreamingFromCachePerPolicy(policy, maxBudget);
                    mergeToFirstMap(fillInWithCacheHits, hitsInCachePerPolicy);

// Second, from backhaul  
                    maxBudget = Math.round(minSCorBHRateSlice / chunkSizeInBytes);
                    mergeToFirstMap(fillInWithDownloadedFromBH,
                            streamCacheMissedFromBH(
                                    policy,
                                    maxBudget,
                                    hitsInCachePerPolicy
                            ));
                }
// Third and last, consumeTry from the macro
                maxBudget = Math.round(mcRateSlice / chunkSizeInBytes);
                mergeToFirstMap(fillInWithDownloadedFromMC, super.consumeFromMCwSCCon(policy,
                        maxBudget)
                );
            }
        }

    }

    /**
     *
     *  maxBudget the max number of chunks that can be downloaded from the
     * cache
     *  fillWithMissedByPolicy for some polices, some chunks may be
     * missed. These chunks must be considered for download by the BH or the MC
     * @return the consumed chunks from the cache
     */
    private Map<AbstractCachingModel, List<Chunk>> tryStreamingFromCachePerPolicy(
            AbstractCachingModel policy,
            long maxBudget) {
        Map<AbstractCachingModel, List<Chunk>> currentHitsInCachePerPolicy = new HashMap<>(5);

        List<Chunk> unconsumed = _unconsumedChunksInSequence.get(policy);
        if (unconsumed.isEmpty()) {
            if (_completitionTimes.get(policy) == -1) {
                _completitionTimes.put(policy, simTime());
                _uncompletedPolicies--;
            }
            return currentHitsInCachePerPolicy;
        }

        long budgetForPolicy = maxBudget;

        ArrayList<Chunk> currentChunkHits;
        currentHitsInCachePerPolicy.put(policy, currentChunkHits = new ArrayList<>());
        List<Chunk> historyChunkHits = _chunksHitsHistoryFromSC.get(policy);

        Iterator<Chunk> unconsumedIt = unconsumed.iterator(); // in ascending order of keys
        Set<Chunk> cachedChunks = _requesterUser.getCurrentlyConnectedSC().cachedChunksUnmodifiable(policy);
        while (unconsumedIt.hasNext() && budgetForPolicy-- > 0) {
            Chunk chunkConsumed = unconsumedIt.next();
            if (!cachedChunks.contains(chunkConsumed)) {
                // bandwidth got wasted
                // if not in the cache, skip
                continue;
            }
            unconsumedIt.remove();
            /* While being connected, only in this case the chunk is not 
                     * already consumed either from the BH nor from MC */
            historyChunkHits.add(chunkConsumed);
            currentChunkHits.add(chunkConsumed);


        }


        if (unconsumed.isEmpty()) {
            if (_completitionTimes.get(policy) == -1) {
                _completitionTimes.put(policy, simTime());
                _uncompletedPolicies--;
            }
        }

        return currentHitsInCachePerPolicy;
    }

    private Map<AbstractCachingModel, List<Chunk>> streamCacheMissedFromBH(
            AbstractCachingModel policy,
            long maxbudget, Map<AbstractCachingModel, List<Chunk>> cacheHitsPerPolicy
    ) {

        Map<AbstractCachingModel, List<Chunk>> bhCurrentConsumptionPerPolicy = new HashMap<>(5);

        List<Chunk> bhCurrentConsumption;
        bhCurrentConsumptionPerPolicy.put(policy, bhCurrentConsumption = new ArrayList<>());
        List<Chunk> bhHistororyConsumption = _chunksConsumedHistoryFromBH.get(policy);

        List<Chunk> unconsumed = _unconsumedChunksInSequence.get(policy);
        if (unconsumed.isEmpty()) {
            if (_completitionTimes.get(policy) == -1) {
                _completitionTimes.put(policy, simTime());
                _uncompletedPolicies--;
            }
            return bhCurrentConsumptionPerPolicy;
        }

        // each policy has its own budget, i.e. if you have 10 slots in the
        // sc wireless and you have 4 hits in the cache, then you have
        // consumed 4 slots in the wireless. Thus, now you can use only 
        // six slots for this policy.
        List<Chunk> hitChunks = cacheHitsPerPolicy.get(policy);
        int hitsNum = hitChunks == null ? 0 : hitChunks.size();
        long budgetForPolicy = maxbudget - hitsNum;

        
        Iterator<Chunk> unconsumedIt = getUnconsumedChunksInSequence(policy).iterator(); // in ascending order of keys
        while (unconsumedIt.hasNext() && budgetForPolicy-- > 0) {
            Chunk chunkConsumed = unconsumedIt.next();

            unconsumedIt.remove();
            bhHistororyConsumption.add(chunkConsumed);
            bhCurrentConsumption.add(chunkConsumed);
        }

        if (unconsumed.isEmpty()) {
            if (_completitionTimes.get(policy) == -1) {
                _completitionTimes.put(policy, simTime());
                _uncompletedPolicies--;
            }
        }
        return bhCurrentConsumptionPerPolicy;
    }

}
