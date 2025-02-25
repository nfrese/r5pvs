package com.conveyal.mytests;

import com.conveyal.osmlib.OSM;
import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.FreeFormPointSet;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.analyst.WebMercatorExtents;
import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.kryo.KryoNetworkSerializer;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.Split;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.streets.StreetRouter.State;
import com.conveyal.r5.streets.VertexStore.VertexFlag;
import com.conveyal.r5.transit.TransitLayer.EntityRepresentation;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.path.RouteSequence.TransitLeg;

import gnu.trove.TIntCollection;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MyR5Test {

    /** Test that subgraphs are removed as expected */
    @Test
    public void testSubgraphRemoval () {
        OSM osm = new OSM(null);
        osm.intersectionDetection = true;
        osm.readFromUrl(MyR5Test.class.getResource("subgraph.pbf").toString());

        StreetLayer sl = new StreetLayer();
        // load from OSM and don't remove floating subgraphs
        sl.loadFromOsm(osm, false, true);

        sl.buildEdgeLists();

      }
    
    @Test
    public void testRoute() throws Exception {
    	
    	
        File dir = new File(System.getenv("MYR5TEST_DATADIR"));

        if (!dir.isDirectory() && dir.canRead()) {
            throw new RuntimeException("is not a readable directory: "+ dir);
        }
    	
        TransportNetwork transportNetwork = KryoNetworkSerializer.read(new File(dir, "network.dat"));
        transportNetwork.readOSM(new File(dir, "osm.mapdb"));
        transportNetwork.transitLayer.buildDistanceTables(null);

        AnalysisWorkerTask task = new RegionalTask() {

			@Override
			public Type getType() {
				return Type.TRAVEL_TIME_SURFACE;
			}

			@Override
			public WebMercatorExtents getWebMercatorExtents() {
				return WebMercatorExtents.forBufferedWgsEnvelope(transportNetwork.getEnvelope(), 10 );
			}

			@Override
			public int nTargetsPerOrigin() {
				return 10;
			}};
			
			
		task.fromLat = 48.4526522; 
		task.fromLon = 16.6000785;
		task.date = LocalDate.of(2025,2,5);
		task.fromTime = 6 * 60 * 60;
		task.maxTripDurationMinutes = 30;
		task.monteCarloDraws = 10;
		task.toTime = 19 * 60 * 60;
		task.transitModes = EnumSet.of(TransitModes.BUS);
		task.accessModes = EnumSet.of(LegMode.WALK);
		task.directModes = EnumSet.of(LegMode.WALK);
		task.egressModes = EnumSet.of(LegMode.WALK);
		task.includePathResults = true;
		task.percentiles = new int[] {1,25,50,75,99};
		task.cutoffsMinutes = new int[] {5,10,15,20,25};
		task.destinationPointSets = new PointSet[] { new FreeFormPointSet(new Coordinate(16.5915001, 48.4781261)) };
		TravelTimeComputer ttc = new TravelTimeComputer(task, transportNetwork);
		OneOriginResult result = ttc.computeTravelTimes();
		
		System.out.println(result +"");
		
		
		for (var path: result.paths.iterationsForPathTemplates)
		{
			System.out.println(path.entries() +"");
			for (var entry : path.entries())
			{
				Collection<TransitLeg> legs = entry.getKey().transitLegs(transportNetwork.transitLayer);
				
				System.out.println("departureTime:" + entry.getValue().departureTime +"");

				System.out.println("totalTime:" + entry.getValue().totalTime/60 +" min");
				System.out.println("waitTime:" + entry.getValue().waitTimes +" s");
								
				System.out.println("routes:" + entry.getKey().routes);
				for (var route : entry.getKey().routes.toArray())
				{
					System.out.println("route:" + transportNetwork.transitLayer.routeString(route, EntityRepresentation.NAME_AND_ID));
				}
				
				System.out.println("stops:" + entry.getKey().stopSequence);
				
			}
		}

    }

    /**
     * Tests if flags, speeds and names are correctly set on split edges
     *
     * @throws Exception
     */
    @Test
    public void testSplits() throws Exception {
        OSM osm = new OSM(null);
        osm.intersectionDetection = true;
        osm.readFromUrl("file:/Users/norbert/work/qop/pbf/weinviertel-latest.osm.pbf");
        StreetLayer streetLayer = new StreetLayer();
        streetLayer.loadFromOsm(osm, false, true);
        osm.close();

        //This is needed for inserting new vertices around coordinates
        streetLayer.indexStreets();

        // http://209.38.180.195:4380/qop/rest/api/route?lng=48.4526522&lat=16.6000785&dest_lat=48.4781261&dest_lng=16.5915001&username=api&password=zrS/NVPqlIUwSjcU
        
        Split splitStart = streetLayer.findSplit(16.6000785, 48.4526522, 30, StreetMode.BICYCLE);
        
        StreetRouter r = new StreetRouter(streetLayer);
        r.setOrigin(48.4526522, 16.6000785);
        r.setDestination(48.4781261, 16.5915001);
        r.route();
        Split destSplit = r.getDestinationSplit();
        
        State state = r.getState(destSplit);
        
        System.out.println(state.distance + " v=" +  + state.vertex);
        
        State prevState = state;
        
        while ((prevState = prevState.backState) != null)
        {
        	System.out.println(prevState.distance + " v=" + prevState.vertex);
        	
        }
        
        
    }
    
    private int connectedVertices(StreetLayer sl, int vertexId) {
        StreetRouter r = new StreetRouter(sl);
        r.setOrigin(vertexId);
        r.route();
        return r.getReachedVertices().size();
    }

    /**
     * We have decided to tolerate OSM data containing ways that reference missing nodes, because geographic extract
     * processes often produce data like this. Load a file containing a way that ends with some missing nodes
     * and make sure no exception occurs. The input must contain ways creating intersections such that at least one
     * edge is produced, as later steps expect the edge store to be non-empty. The PBF fixture for this test is derived
     * from the hand-tweaked XML file of the same name using osmconvert.
     */
    @Test
    public void testMissingNodes () {
        OSM osm = new OSM(null);
        osm.intersectionDetection = true;
        osm.readFromUrl(MyR5Test.class.getResource("missing-nodes.pbf").toString());
        assertDoesNotThrow(() -> {
            StreetLayer sl = new StreetLayer();
            sl.loadFromOsm(osm, true, true);
            sl.buildEdgeLists();
        });
    }

}
