package com.telenav.osv.manager.location;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import javax.inject.Inject;
import android.location.Location;
import com.skobbler.ngx.SKCoordinate;
import com.skobbler.ngx.map.SKBoundingBox;
import com.telenav.osv.event.EventBus;
import com.telenav.osv.event.network.matcher.BoundingBoxChangedEvent;
import com.telenav.osv.event.network.matcher.CoverageEvent;
import com.telenav.osv.event.network.matcher.SegmentsReceivedEvent;
import com.telenav.osv.item.Polyline;
import com.telenav.osv.item.Segment;
import com.telenav.osv.item.network.GeometryCollection;
import com.telenav.osv.listener.network.NetworkResponseDataListener;
import com.telenav.osv.manager.network.GeometryRetriever;
import com.telenav.osv.utils.BackgroundThreadPool;
import com.telenav.osv.utils.ComputingDistance;
import com.telenav.osv.utils.Log;
import com.telenav.osv.utils.Utils;

/**
 * Component that matches given positions to segments received from backend
 * Created by Kalman on 31/10/16.
 */
public class PositionMatcher {

    private static final String TAG = "PositionMatcher";

    /**
     * skmaps sdk max zoom, used for the segment request as a FULL RESOLUTION flag (no simplification applied to segments received)
     */
    private static final float MAX_RESOLUTION_ZOOM = 19;

    /**
     * 15 meters
     */
    private static final double MATCH_DISTANCE_LIMIT = 15d / 110000d;

    private static final int TWO_SEC = 2000;

    private static final int MAX_POSITIONS_HISTORY = 5;

    private static final double[] HEADING_WEIGHTS = new double[]{2, 4, 8, 16};

    private final GeometryRetriever mGeometryRetriever;

    private final Object matcherSyncObject = new Object();

    private SegmentsListener mSegmentsListener;

    /**
     * if this bounding box is reached by the small bounding boxes edge, then a new request is sent
     */
    private SKBoundingBox mTriggerBB;

    /**
     * the area that we have segment/coverage data for
     */
    private SKBoundingBox mLoadedBB;

    /**
     * the list holding the loaded bounding box's segments
     */
    private ArrayList<Polyline> mPolylines;

    /**
     * true if a request has been made but did not arrived yet
     */
    private boolean requestSent = false;

    /**
     * we match every 2 seconds only if not recording, to reduce battery consumption
     */
    private long mLastCheckTime;

    private LinkedList<SKCoordinate> mLastCoordinates = new LinkedList<>();

    @Inject
    public PositionMatcher(GeometryRetriever geometryRetriever) {
        mGeometryRetriever = geometryRetriever;
    }

    /**
     * returns a bb around the position so
     * (bb.width == dist*2) && (bb.height == dist*2)
     * is true
     * @param coords
     * @param dist the
     * @return
     */
    static SKBoundingBox getBoundingBoxForRegion(SKCoordinate coords, double dist) {
        return getBoundingBox(coords.getLatitude(), coords.getLongitude(), dist);
    }

    /**
     * checks if lat long is inside the bb
     * @param bb
     * @param lat
     * @param lon
     * @return
     */
    private static boolean pointIsInBB(SKBoundingBox bb, double lat, double lon) {
        return bb != null &&
                (bb.getTopLeft().getLatitude() >= lat && lat >= bb.getBottomRight().getLatitude() && bb.getTopLeft().getLongitude() <= lon &&
                        lon <= bb.getBottomRight().getLongitude());
    }

    private static SKBoundingBox getBoundingBox(double lat, double lng, double meters) {
        double topLeftLat = lat + getMercFromDist(meters);
        double topLeftLon = lng - getMercFromDist(meters);
        double bottomRightLat = lat - getMercFromDist(meters);
        double bottomRightLon = lng + getMercFromDist(meters);
        return new SKBoundingBox(new SKCoordinate(topLeftLat, topLeftLon), new SKCoordinate(bottomRightLat, bottomRightLon));
    }

    private static double getMercFromDist(double meters) {
        return meters / 110000;
    }

    private static double calculateBearing(SKCoordinate coord1, SKCoordinate coord2) {
        double longDiff = coord2.getLongitude() - coord1.getLongitude();
        double y = Math.sin(longDiff) * Math.cos(coord2.getLatitude());
        double x = Math.cos(coord1.getLatitude()) * Math.sin(coord2.getLatitude()) -
                Math.sin(coord1.getLatitude()) * Math.cos(coord2.getLatitude()) * Math.cos(longDiff);

        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    public void setListener(SegmentsListener listener) {
        mSegmentsListener = listener;
    }

    private void requestNewDataIfNeeded(SKBoundingBox smallBB, final SKCoordinate coordinate) {
        if (assessBBDifference(mTriggerBB, smallBB) && !requestSent && mGeometryRetriever != null) {
            requestSent = true;
            Log.d(TAG, "requestNewDataIfNeeded: loading segments for " + " coordinate " + coordinate);
            final SKBoundingBox requestedBB = getBoundingBoxForRegion(coordinate, 3500);
            final SKBoundingBox triggerBB = getBoundingBoxForRegion(coordinate, 1500);
            Log.d(TAG, "requestNewDataIfNeeded: " + triggerBB);
            BackgroundThreadPool.post(() -> {
                Log.d(TAG, "requestNewDataIfNeeded: sending request");

                mGeometryRetriever.listSegments(new NetworkResponseDataListener<GeometryCollection>() {

                                                    @Override
                                                    public void requestFailed(int status, GeometryCollection details) {
                                                        Log.d(TAG, "requestNewDataIfNeeded: " + details);
                                                        requestSent = false;
                                                    }

                                                    @Override
                                                    public void requestFinished(int status, GeometryCollection collectionData) {
                                                        offerNewSegments(coordinate, collectionData.getSegmentList(), triggerBB, requestedBB);
                                                    }
                                                }, requestedBB.getTopLeft().getLatitude() + "," + requestedBB.getTopLeft().getLongitude(),
                        requestedBB.getBottomRight().getLatitude() + "," + requestedBB.getBottomRight().getLongitude(),
                        MAX_RESOLUTION_ZOOM);
            });
        }
    }

    private boolean assessBBDifference(SKBoundingBox mLastBB, SKBoundingBox bbnormal) {
        double bottomLat = bbnormal.getBottomRight().getLatitude();
        double bottomLon = bbnormal.getBottomRight().getLongitude();
        double topLat = bbnormal.getTopLeft().getLatitude();
        double topLon = bbnormal.getTopLeft().getLongitude();
        if (mLastBB == null) {
            Log.d(TAG, "assessBBDifference: previous bb null");
            return true;
        }
        boolean changed = !pointIsInBB(mLastBB, bottomLat, bottomLon) || !pointIsInBB(mLastBB, topLat, topLon);
        if (!changed) {
            Log.d(TAG, "assessBBDifference: not changed enough");
        }
        return changed;
    }

    public interface SegmentsListener {

        void onSegmentsReceived(SKCoordinate location);
    }

    /**
     * called for the current location, every 2 seconds max
     * @param location
     * @return
     */
    Segment onLocationChanged(Location location) {
        long currentTime = System.currentTimeMillis();
        if ((currentTime - mLastCheckTime > TWO_SEC)) {
            mLastCheckTime = currentTime;
            final SKCoordinate coordinate = new SKCoordinate(location.getLatitude(), location.getLongitude());
            SKBoundingBox smallBB = getBoundingBoxForRegion(coordinate, 500);
            //send request if needed
            requestNewDataIfNeeded(smallBB, coordinate);
            if (Utils.DEBUG) {
                //if debug enabled, the boxes will be rendered on the map
                EventBus.post(new BoundingBoxChangedEvent(mTriggerBB, mLoadedBB, smallBB));
            }
            if (isCoverageForLocation(coordinate)) {
                return match(coordinate);
            } else {
                Log.d(TAG, "onLocationChanged: location not covered");
            }
        }
        return null;
    }

    /**
     * called for the position of taken pictures
     * @param location
     * @return
     */
    Segment onPictureTaken(Location location) {
        //        if (event.location.getAccuracy() > 500){todo what happens with tunnel mode pictures?
        //            return;
        //        }
        final SKCoordinate coordinate = new SKCoordinate(location.getLatitude(), location.getLongitude());
        SKBoundingBox smallBB = getBoundingBoxForRegion(coordinate, 500);
        //send request if needed
        requestNewDataIfNeeded(smallBB, coordinate);
        if (Utils.DEBUG) {
            //if debug enabled, the boxes will be rendered on the map
            EventBus.post(new BoundingBoxChangedEvent(mTriggerBB, mLoadedBB, smallBB));
        }
        boolean locationIsCovered = isCoverageForLocation(coordinate);
        if (locationIsCovered) {
            //======================================================
            //match the position
            Log.d(TAG, "onPictureTaken: matching on local data");
            synchronized (matcherSyncObject) {
                return match(coordinate);
            }
        } else {
            //======================================================
            //cannot match the position
            Log.d(TAG, "onPictureTaken: location not covered");
            return null;
        }
        //==========================================================
        //display debug overlays
    }

    void offerNewSegments(SKCoordinate coordinate, List<Polyline> segments, SKBoundingBox triggerBB, SKBoundingBox requestedBB) {
        mTriggerBB = triggerBB;
        mLoadedBB = requestedBB;
        synchronized (matcherSyncObject) {
            if (mPolylines == null) {
                mPolylines = new ArrayList<>();
            }
            mPolylines.clear();
            mPolylines.addAll(segments);
            if (mPolylines.isEmpty()) {
                Log.d(TAG, "requestNewDataIfNeeded: no segments were recived");
            }
            Log.d(TAG, "requestNewDataIfNeeded: request " + mPolylines.size() + " segments received");
            requestSent = false;
            Log.d(TAG, "requestNewDataIfNeeded: segments received");
            mSegmentsListener.onSegmentsReceived(coordinate);
            isCoverageForLocation(coordinate);
            broadcastSegments();
        }
    }

    void broadcastSegments() {
        EventBus.postSticky(new SegmentsReceivedEvent(mPolylines, matcherSyncObject, true, mLoadedBB, MAX_RESOLUTION_ZOOM));
    }

    boolean isCoverageForLocation(SKCoordinate coordinate) {
        boolean isCoverage = pointIsInBB(mLoadedBB, coordinate.getLatitude(), coordinate.getLongitude());
        if (isCoverage) {
            EventBus.postSticky(new CoverageEvent(true));
        } else {
            EventBus.postSticky(new CoverageEvent(false));
        }
        return isCoverage;
    }

    Segment match(SKCoordinate coordinate) {
        double historicalBearing = getBearing(coordinate);
        if (mPolylines != null) {
            synchronized (matcherSyncObject) {
                long time = System.currentTimeMillis();
                ArrayList<Segment> candidates = new ArrayList<>();
                for (Polyline polyline : mPolylines) {
                    List<SKCoordinate> track = polyline.getNodes();
                    if (coordinate != null && !track.isEmpty()) {
                        for (int i = 0; i < track.size() - 1; i++) {
                            SKCoordinate coord1 = track.get(i);
                            SKCoordinate coord2 = track.get(i + 1);
                            double distanceToPos = ComputingDistance.getDistanceFromSegment(coordinate, coord1, coord2);
                            if (distanceToPos < MATCH_DISTANCE_LIMIT) {
                                double dist1 = ComputingDistance.distanceBetween(coord1, coordinate);
                                double dist2 = ComputingDistance.distanceBetween(coord2, coordinate);

                                SKCoordinate firstCoord;
                                SKCoordinate secondCoord;
                                if (dist1 < dist2) {
                                    firstCoord = coord1;
                                    secondCoord = coord2;
                                } else {
                                    firstCoord = coord2;
                                    secondCoord = coord1;
                                }
                                double bearing = calculateBearing(firstCoord, secondCoord);
                                double altBearing = (bearing + 180) % 360;
                                if ((distanceToPos < dist1 || distanceToPos < dist2) &&
                                        (Math.abs(bearing - historicalBearing) > Math.abs(altBearing - historicalBearing))) {
                                    bearing = altBearing;
                                    SKCoordinate temp = firstCoord;
                                    firstCoord = secondCoord;
                                    secondCoord = temp;
                                }
                                double deltaBearing = Math.abs(bearing - historicalBearing);
                                Log.d(TAG, "match:" + polyline.getIdentifier() + " bearing for " + i + " is " + bearing + " delta is " + deltaBearing);

                                //                          0-20                0-180
                                float score = (float) (((distanceToPos / MATCH_DISTANCE_LIMIT) * 50.0 + (deltaBearing / 180) * 50.0) / 100f);
                                candidates.add(new Segment(distanceToPos, coordinate, polyline, firstCoord, secondCoord, deltaBearing, score));
                            }
                        }
                    }
                }

                Segment bestCandidate = null;
                float bestScore = 100000f;
                for (Segment segment : candidates) {
                    if (segment.getScore() < bestScore) {
                        bestScore = segment.getScore();
                        bestCandidate = segment;
                    }
                }

                Log.d(TAG, "match: matched segment called on " + mPolylines.size() + " segments, run in " + (System.currentTimeMillis() - time));

                if (bestCandidate == null) {
                    Polyline polyline = new Polyline(404);
                    polyline.coverage = 0;
                    return new Segment(1000, coordinate, polyline, new SKCoordinate(), new SKCoordinate(), 0, 0);
                } else {
                    return bestCandidate;
                }
            }
        }
        return null;
    }

    double getBearing(SKCoordinate coordinate) {
        offerLocationForBearing(coordinate);
        double totalWeight = 0;
        double totalWeightedBearing = 0;
        for (int i = 0; i < mLastCoordinates.size() - 1; i++) {
            SKCoordinate coord1 = mLastCoordinates.get(i);
            SKCoordinate coord2 = mLastCoordinates.get(i + 1);
            double bearing = calculateBearing(coord1, coord2);
            double weight = HEADING_WEIGHTS[i];
            totalWeight += weight;
            totalWeightedBearing += bearing * weight;
        }
        double bearing = 0;
        if (totalWeight > 0) {
            bearing = totalWeightedBearing / totalWeight;
        }
        Log.d(TAG, "getBearing: " + bearing);
        return bearing;
    }

    void offerLocationForBearing(SKCoordinate coordinate) {
        mLastCoordinates.addLast(coordinate);
        while (mLastCoordinates.size() > MAX_POSITIONS_HISTORY) {
            mLastCoordinates.pollFirst();
        }
    }
}
