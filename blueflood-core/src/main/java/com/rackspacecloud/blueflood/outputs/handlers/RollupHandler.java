/*
 * Copyright 2013 Rackspace
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.rackspacecloud.blueflood.outputs.handlers;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import com.rackspacecloud.blueflood.io.AstyanaxReader;
import com.rackspacecloud.blueflood.outputs.formats.MetricData;
import com.rackspacecloud.blueflood.rollup.Granularity;
import com.rackspacecloud.blueflood.types.*;
import com.rackspacecloud.blueflood.utils.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

public class RollupHandler {
    private static final Logger log = LoggerFactory.getLogger(RollupHandler.class);

    protected final Meter rollupsByPointsMeter = Metrics.meter(RollupHandler.class, "BF-API", "Get rollups by points");
    protected final Meter rollupsByGranularityMeter = Metrics.meter(RollupHandler.class, "BF-API", "Get rollups by gran");
    protected final Timer metricsForCheckTimer = Metrics.timer(RollupHandler.class, "Get metrics for check");
    protected final Timer metricsFetchTimer = Metrics.timer(RollupHandler.class, "Get metrics from db");
    protected final Timer rollupsCalcOnReadTimer = Metrics.timer(RollupHandler.class, "Rollups calculation on read");
    protected final Histogram numFullPointsReturned = Metrics.histogram(RollupHandler.class, "Full res points returned");
    protected final Histogram numRollupPointsReturned = Metrics.histogram(RollupHandler.class, "Rollup points returned");
    protected final Histogram numHistogramPointsReturned = Metrics.histogram(RollupHandler.class, "Histogram points returned");

    protected MetricData getRollupByGranularity(
            String tenantId,
            String metricName,
            long from,
            long to,
            Granularity g) {

        final Timer.Context ctx = metricsFetchTimer.time();
        final Locator locator = Locator.createLocatorFromPathComponents(tenantId, metricName);
        final MetricData metricData = AstyanaxReader.getInstance().getDatapointsForRange(
                locator,
                new Range(g.snapMillis(from), to),
                g);

        // if Granularity is FULL, we are missing raw data - can't generate that
        if (g != Granularity.FULL && metricData != null) {
            final Timer.Context rollupsCalcCtx = rollupsCalcOnReadTimer.time();

            long latest = from;
            Map<Long, Points.Point> points = metricData.getData().getPoints();
            for (Map.Entry<Long, Points.Point> point : points.entrySet()) {
                if (point.getValue().getTimestamp() > latest) {
                    latest = point.getValue().getTimestamp();
                }
            }

            // timestamp of the end of the latest slot
            if (latest + g.milliseconds() <= to) {
                // missing some rollups, generate more (5 MIN rollups only)
                for (Range r : Range.rangesForInterval(g, latest + g.milliseconds(), to)) {
                    try {
                        MetricData data = AstyanaxReader.getInstance().getDatapointsForRange(locator, r, Granularity.FULL);
                        Points<SimpleNumber> dataToRoll = data.getData();
                        BasicRollup rollup = Rollup.BasicFromRaw.compute(dataToRoll);
                        if (rollup.getCount() > 0) {
                            metricData.getData().add(new Points.Point<BasicRollup>(r.getStart(), rollup));
                        }

                    } catch (IOException ex) {
                        log.error("Exception computing rollups during read: ", ex);
                    }
                }
            }
            rollupsCalcCtx.stop();
        }
        ctx.stop();

        if (g == Granularity.FULL) {
            numFullPointsReturned.update(metricData.getData().getPoints().size());
        } else {
            numRollupPointsReturned.update(metricData.getData().getPoints().size());
        }

        return metricData;
    }

    protected MetricData getHistogramsByGranularity(String tenantId,
                                                   String metricName,
                                                   long from,
                                                   long to,
                                                   Granularity g) throws IOException {
        if (!g.isCoarser(Granularity.FULL)) {
            throw new IOException("Histograms are not available for this granularity");
        }

        final Timer.Context ctx = metricsFetchTimer.time();
        final Locator locator = Locator.createLocatorFromPathComponents(tenantId, metricName);

        MetricData data;
        try {
            data = AstyanaxReader.getInstance().getHistogramsForRange(locator, new Range(g.snapMillis(from), to), g);
            numHistogramPointsReturned.update(data.getData().getPoints().size());
        } finally {
            ctx.stop();
        }

        return data;
    }
}
