package com.conveyal.r5.point_to_point;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.locationtech.jts.geom.Coordinate;

import com.conveyal.analysis.models.CsvResultOptions;
import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.FreeFormPointSet;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.analyst.WebMercatorExtents;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.databind.ObjectMapper;

import spark.Request;
import spark.Response;

public class SingleStartRequest {

	public static class RouteInfos {
		public int routeId;
		public String routeName;
		public String routeLongName;
		public int routeType;
	}

	public static class StopInfos {
		public int stopId;
		public String name;
	}

	public static class RouteStats {
		public int routeId;
		public int count=0;
		public double minDuration=Double.MAX_VALUE;
		public String accessMode;
		public int accessTime;
		public String egressMode;
		public int egressTime;
		public int departureTime;
		public int[] boardStops;
		public int[] alightStops;
		public int[] rideTimesSeconds;
	}

	public static Object handleSinglePoint (Request request, Response response, TransportNetwork transportNetwork) throws IOException {

		String sources = request.queryParams("sources");
		String destinations = request.queryParams("destinations");

		var sourceCoordinates = PointToPointRouterServer.paramToCoordinates(sources);
		var destCoordinates = PointToPointRouterServer.paramToCoordinates(destinations);

		if (sourceCoordinates.size() != 1)
		{
			throw new RuntimeException("1 pair of source coordinates expected, got "+ sources);
		}

		RegionalTask task = new RegionalTask() {

			private static final long serialVersionUID = 1L;

			@Override
			public WebMercatorExtents getWebMercatorExtents() {
				return WebMercatorExtents.forBufferedWgsEnvelope(transportNetwork.streetLayer.getEnvelope(), 10 );
			}

		};

		task.zoom = 10;
		task.fromLat = sourceCoordinates.get(0).y; 
		task.fromLon = sourceCoordinates.get(0).x;
		task.date = LocalDate.of(2025,2,5);
		task.fromTime = 7 * 60 * 60;
		task.maxTripDurationMinutes = 30;
		//task.monteCarloDraws = 10;
		task.toTime = 9 * 60 * 60;
		task.transitModes = EnumSet.of(TransitModes.BUS);
		task.accessModes = EnumSet.of(LegMode.WALK);
		task.directModes = EnumSet.of(LegMode.WALK);
		task.oneToOne=false;
		task.egressModes = EnumSet.of(LegMode.WALK);
		task.includePathResults = true;
		task.percentiles = new int[] {1,25,50,75,99};
		task.cutoffsMinutes = new int[] {5,10,15,20,25};
		task.destinationPointSets = new PointSet[] { new FreeFormPointSet(destCoordinates.toArray(new Coordinate[0])) };
		task.csvResultOptions = new CsvResultOptions();
		task.dualAccessibilityThreshold = 10;

		response.header("Content-Encoding", "gzip");
		if (task.logRequest){
			PointToPointRouterServer.LOG.info(request.body());
		}
		try {

			TravelTimeComputer computer = new TravelTimeComputer(task, transportNetwork);
			OneOriginResult oneOriginResult = computer.computeTravelTimes();

			Set<Integer> occurRoutes = new TreeSet<>();
			Set<Integer> occurStops = new TreeSet<>();

			List<Map<String,Object>> results = new ArrayList<>(); 

			for (var path : oneOriginResult.paths.iterationsForPathTemplates)
			{
				double min = Double.MAX_VALUE;
				Map<Integer, SingleStartRequest.RouteStats> routeCnt = new TreeMap<>();

				System.out.println(path);
				for (var iter : path.entries()) {
					System.out.println(iter);
					if (iter.getKey().routes.size()>0) {
						for (var it = iter.getKey().routes.iterator(); it.hasNext();)
						{
							int r = it.next();
							SingleStartRequest.RouteStats rc = routeCnt.get(r);
							if (rc == null)
							{
								rc = new SingleStartRequest.RouteStats();
								routeCnt.put(r, rc);

								rc.routeId = r;
								rc.accessMode = ""+iter.getKey().stopSequence.access.mode;
								rc.accessTime = iter.getKey().stopSequence.access.time;
								rc.boardStops = iter.getKey().stopSequence.boardStops.toArray();
								rc.alightStops = iter.getKey().stopSequence.alightStops.toArray();
								rc.rideTimesSeconds = iter.getKey().stopSequence.rideTimesSeconds.toArray();

								rc.egressMode = ""+iter.getKey().stopSequence.egress.mode;
								rc.egressTime = iter.getKey().stopSequence.egress.time;

								rc.departureTime = iter.getValue().departureTime;

								for (var stop : rc.alightStops)
								{
									occurStops.add(stop);
								}
								for (var stop : rc.boardStops)
								{
									occurStops.add(stop);
								}

								occurRoutes.add(r);
							}
							rc.count++;
							rc.minDuration = Math.min(rc.minDuration, iter.getValue().totalTime);
						}
					}
					min = Math.min(min, iter.getValue().totalTime);            		
				}

				Map<String, Object> r = new TreeMap<>();

				r.put("minTotalTime", min);
				r.put("routes", routeCnt.values());


				for (var rc : routeCnt.values())
				{
					if (min == rc.minDuration)
					{
						r.put("bestRoute", rc);
					}
				}

				results.add(r);
			}


			Map<String,Object> routeInfos = new LinkedHashMap<String, Object>();

			for (var r: occurRoutes) {
				RouteInfo ri = 
						transportNetwork.transitLayer.routes.get(r);

				SingleStartRequest.RouteInfos rc = new SingleStartRequest.RouteInfos();
				rc.routeId = r;
				rc.routeName = ri.route_short_name;
				rc.routeLongName = ri.route_long_name;   
				rc.routeType = ri.route_type;
				routeInfos.put(r+"", rc);
			}

			Map<String,Object> stopInfos = new LinkedHashMap<String, Object>();

			for (var s: occurStops) {
				SingleStartRequest.StopInfos rc = new SingleStartRequest.StopInfos();
				rc.stopId = s;
				rc.name = transportNetwork.transitLayer.stopNames.get(s);

				stopInfos.put(s+"", rc);
			}

			Map<String,Object> resultCont = new LinkedHashMap<String, Object>();
			resultCont.put("results", results);
			resultCont.put("routeInfos", routeInfos);
			resultCont.put("stopInfos", stopInfos);

			response.header("Content-Type", "application/json");

			var json = new ObjectMapper().writeValueAsString(resultCont);
			return json;

		} catch (Throwable throwable) {
			throw new RuntimeException(throwable);
		}
	}

}
