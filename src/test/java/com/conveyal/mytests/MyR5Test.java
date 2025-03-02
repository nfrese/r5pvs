package com.conveyal.mytests;

import com.conveyal.analysis.models.CsvResultOptions;
import com.conveyal.analysis.util.JsonUtil;
import com.conveyal.osmlib.OSM;
import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.FreeFormPointSet;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.analyst.WebMercatorExtents;
import com.conveyal.r5.analyst.cluster.AnalysisWorker;
import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;
import com.conveyal.r5.analyst.cluster.TimeGridWriter;
import com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask;
import com.conveyal.r5.analyst.decay.StepDecayFunction;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.common.JsonUtilities;
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
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.io.LittleEndianDataOutputStream;

import gnu.trove.TIntCollection;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.IntStream;

import static com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask.Format.GEOTIFF;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MyR5Test {

    @Test
    public void testParseRequest () throws JsonParseException, JsonMappingException, UnsupportedEncodingException, IOException {
    	
    	String requestBody = """
    			
    			""";
    	TravelTimeSurfaceTask task = JsonUtilities.lenientObjectMapper.readValue(requestBody.getBytes("UTF-8"), TravelTimeSurfaceTask.class);

    	
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

        TravelTimeSurfaceTask task = new TravelTimeSurfaceTask() {
        	public WebMercatorExtents getWebMercatorExtents() {
        		return WebMercatorExtents.forBufferedWgsEnvelope(transportNetwork.getEnvelope(), 10 );
        	}
        	;
        };
		task.width=1;
		task.height=1;
		task.north = 1;
		task.west = 1;
		task.zoom = 10;
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
		task.cutoffsMinutes = IntStream.rangeClosed(0, 120).toArray(); //new int[] {5,10,15,20,25};
		task.destinationPointSetKeys = new String[] {"psone"};
		task.destinationPointSets = new PointSet[] { new FreeFormPointSet(new Coordinate(16.5915001, 48.4781261)) };
		task.decayFunction = new StepDecayFunction();
		
		
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
		
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        TimeGridWriter timeGridWriter = new TimeGridWriter(result.travelTimes, task);
        if (1>2) {
            timeGridWriter.writeGeotiff(byteArrayOutputStream);
        } else {
            // Catch-all, if the client didn't specifically ask for a GeoTIFF give it a proprietary grid.
            // Return raw byte array representing grid to caller, for return to client over HTTP.
            // TODO eventually reuse same code path as static site time grid saving
            // TODO move the JSON writing code into the grid writer, it's essentially part of the grid format
            timeGridWriter.writeToDataOutput(new LittleEndianDataOutputStream(byteArrayOutputStream));
            AnalysisWorker.addJsonToGrid(
                    byteArrayOutputStream,
                    result,
                    transportNetwork.scenarioApplicationWarnings,
                    transportNetwork.scenarioApplicationInfo,
                    transportNetwork.transitLayer
            );
        }
        
        System.out.println(new String(byteArrayOutputStream.toByteArray(), "UTF-8"));

    }

    @Test
    public void testRouteReg() throws Exception {
    	
    	
        File dir = new File(System.getenv("MYR5TEST_DATADIR"));

        if (!dir.isDirectory() && dir.canRead()) {
            throw new RuntimeException("is not a readable directory: "+ dir);
        }
    	
        TransportNetwork transportNetwork = KryoNetworkSerializer.read(new File(dir, "network.dat"));
        transportNetwork.readOSM(new File(dir, "osm.mapdb"));
        transportNetwork.transitLayer.buildDistanceTables(null);

        RegionalTask task = new RegionalTask() {

//			@Override
//			public Type getType() {
//				return Type.TRAVEL_TIME_SURFACE;
//			}

			@Override
			public WebMercatorExtents getWebMercatorExtents() {
				return WebMercatorExtents.forBufferedWgsEnvelope(transportNetwork.streetLayer.getEnvelope(), 10 );
			}

//			@Override
//			public int nTargetsPerOrigin() {
//				return 10;
//			}
		};
			
		task.zoom = 10;
		task.fromLat = 48.4526522; 
		task.fromLon = 16.6000785;
		task.date = LocalDate.of(2025,2,5);
		task.fromTime = 6 * 60 * 60;
		task.maxTripDurationMinutes = 30;
		//task.monteCarloDraws = 10;
		task.toTime = 19 * 60 * 60;
		task.transitModes = EnumSet.of(TransitModes.BUS);
		task.accessModes = EnumSet.of(LegMode.WALK);
		task.directModes = EnumSet.of(LegMode.WALK);
		task.egressModes = EnumSet.of(LegMode.WALK);
		task.includePathResults = true;
		task.percentiles = new int[] {1,25,50,75,99};
		task.cutoffsMinutes = new int[] {5,10,15,20,25};
		task.destinationPointSets = new PointSet[] { new FreeFormPointSet(new Coordinate(16.5915001, 48.4781261)) };
		task.csvResultOptions = new CsvResultOptions();
		TravelTimeComputer ttc = new TravelTimeComputer(task, transportNetwork);
		OneOriginResult result = ttc.computeTravelTimes();
		
		System.out.println(result +"");
		
		
		for (var path: result.paths.iterationsForPathTemplates)
		{
			if (path == null) continue;
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
		
		var pojo = new RegionalWorkResult(result, task);
		
		var json = JsonUtil.objectMapper.writeValueAsString(pojo);
		System.out.println(json);
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
        osm.readFromUrl(System.getenv("MYR5TEST_PBF_URL"));
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
