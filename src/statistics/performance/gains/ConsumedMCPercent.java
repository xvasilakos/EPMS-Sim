package statistics.performance.gains;

import caching.base.AbstractCachingModel;
import static java.lang.Double.NaN;
import java.util.ArrayList;
import java.util.List;
import sim.content.request.DocumentRequest;
import sim.content.Chunk;
import sim.space.cell.smallcell.SmallCell;
import sim.space.users.CachingUser;
import sim.space.users.StationaryUser;
import statistics.StatisticException;
import statistics.handlers.AbstractPerformanceStat;

/**
 * @author Xenofon Vasilakos xvas@aueb.gr
 */
public class ConsumedMCPercent extends AbstractPerformanceStat<CachingUser, SmallCell, DocumentRequest> {

    public ConsumedMCPercent(AbstractCachingModel cachingMethod) {
        super(cachingMethod);
    }

    @Override
    public final double computeGain(CachingUser user, DocumentRequest r) throws StatisticException {
        if (user instanceof StationaryUser) {
            return NaN;// ignore stationaries
        }

        AbstractCachingModel model = getCachingModel();

        List<Chunk> consumedChunksFromSC = r.getChunksCacheHitsHistory(model);
        List<Chunk> consumedChunksFromBH = r.getChunksConsumedHistoryFromBH(model);

        List<Chunk> consumedChunksFromMC = new ArrayList();

        consumedChunksFromMC.addAll(r.getChunksConsumedHistoryFromMCAfterExitingSC(model));
        consumedChunksFromMC.addAll(r.getChunksConsumedHistoryFromMCBeforeEnteringSC(model));
        consumedChunksFromMC.addAll(r.getChunksConsumedHistoryFromMCwSCConn(model));

        double sum = r.getChunksConsumedOverall(model).size();
//                consumedChunksFromSC.size()
//                + consumedChunksFromBH.size()
//                + consumedChunksFromMC.size();

        double all = r.referredContentDocument().chunks().size();

        if (all != sum) {
            throw new RuntimeException(
                    "sum: " + sum
                    + " but #chunks: " + all
            );
        }

        return consumedChunksFromMC.size() / all;
    }

    @Override
    public String title() {
        return "%ConsmMC" + "-" + getCachingModel().nickName();
    }

    @Override
    public String title(String str) {
        return "%ConsmMC" + "_" + str + "_" + getCachingModel().nickName();
    }

}
