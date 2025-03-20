package com.conveyal.r5.point_to_point;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.point_to_point.planner.Trip;
import com.conveyal.r5.point_to_point.planner.TripPlanner;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.databind.ObjectMapper;

import spark.Request;
import spark.Response;

public class PlannerRequest {

	public static class TripLeg {
		public String routeId;
		public String routeLongName;
		public int routeType;
		public int boardStopId;
		public int alightStopId;
		public String mode;
		public int legDistance;
		public int legDurationSeconds;
		public String boardStopName;
		public String alightStopName;
		public String geom;
		public String routeShortName;
	}

	public static class TripInfos {
		public String routeId;
		public int count=0;
		public double totalDurationSeconds=Double.MAX_VALUE;
		public String accessMode;
		public int accessTime;
		public String egressMode;
		public int egressTime;
		public int departureTime;
		public int[] boardStops;
		public int[] alightStops;
		public int[] rideTimesSeconds;
		public String route;

		public List<TripLeg> tripLegs = new ArrayList<>();
		public String routeLongName;
		public String routeShortName;
	}

	public static Object handlePlan (Request request, Response response, TransportNetwork transportNetwork) throws IOException {

		String sources = request.queryParams("sources");
		String destinations = request.queryParams("destinations");

		var sourceCoordinates = PointToPointRouterServer.paramToCoordinates(sources);
		var destCoordinates = PointToPointRouterServer.paramToCoordinates(destinations);

		if (sourceCoordinates.size() != 1)
		{
			throw new RuntimeException("1 pair of source coordinates expected, got "+ sources);
		}

		ProfileRequest task = new ProfileRequest();

		task.fromLat = sourceCoordinates.get(0).y; 
		task.fromLon = sourceCoordinates.get(0).x;
		task.toLat = destCoordinates.get(0).y; 
		task.toLon = destCoordinates.get(0).x;
		task.date = LocalDate.of(2025,2,5);
		task.fromTime = 7 * 60 * 60;
		task.maxTripDurationMinutes = 120;
		task.maxFare = 99999;
		task.toTime = 9 * 60 * 60;
		task.transitModes = EnumSet.of(TransitModes.BUS);
		task.accessModes = EnumSet.of(LegMode.WALK);
		task.directModes = EnumSet.of(LegMode.WALK);
		task.egressModes = EnumSet.of(LegMode.WALK);

		TripPlanner planner = new TripPlanner(transportNetwork, task);

		planner.setShortestPath(false);

		List<Trip> trips = planner.plan();
		List<TripInfos> tis = new ArrayList<>();

		TripInfos bestTrip = null;
		double bestDurationSeconds = Double.MAX_VALUE;

		for (var trip: trips) {

			TripInfos ti = new TripInfos();
			int cnt = 0;
			int nlegs = trip.getLegs().size();
			
			for (var leg : trip.getLegs()) {
				TripLeg tLeg = new TripLeg();
				tLeg.routeId = leg.getRoute();
				tLeg.routeShortName = leg.getRouteShortName();
				tLeg.routeLongName = leg.getRouteLongName();
				tLeg.boardStopId = leg.getBoardStop();
				tLeg.boardStopName = getStopName(transportNetwork, leg.getBoardStop());
				tLeg.alightStopId = leg.getAlightStop();
				tLeg.alightStopName = getStopName(transportNetwork, leg.getAlightStop());
				tLeg.mode = leg.getMode();
				tLeg.legDistance = leg.getLegDistance();
				tLeg.legDurationSeconds = leg.getLegDurationSeconds();
				tLeg.geom = leg.getGeometry()+"";

				ti.tripLegs.add(tLeg);
				
				if (nlegs>2) {
					if (cnt == 1)
					{
						ti.accessMode = tLeg.mode;
						ti.accessTime = tLeg.legDurationSeconds;
					}
					
					if (nlegs-1 == cnt) {
						ti.egressMode = tLeg.mode;
						ti.egressTime = tLeg.legDurationSeconds;
					}
					
					if (cnt == 2 && tLeg.routeId != null) {
						ti.routeId = tLeg.routeId;
						ti.routeLongName = tLeg.routeLongName;
						ti.routeShortName = tLeg.routeShortName;
					}
				}
				
				cnt++;

			}
			ti.departureTime = trip.getDepartureTime();
			ti.totalDurationSeconds = trip.getTotalDurationSeconds();
			tis.add(ti);

			if (ti.totalDurationSeconds < bestDurationSeconds)
			{
				bestTrip = ti;
				bestDurationSeconds = ti.totalDurationSeconds;
			}

		}

		response.header("Content-Encoding", "gzip");

		Map<String,Object> resultCont = new LinkedHashMap<String, Object>();
		resultCont.put("bestTrip", bestTrip);
		resultCont.put("trips", tis);

		response.header("Content-Type", "application/json");

		var json = new ObjectMapper().writeValueAsString(resultCont);
		return json;

	}

	private static String getStopName(TransportNetwork transportNetwork, int stopId) {
		if (stopId != 0)
		{
			return transportNetwork.transitLayer.stopNames.get(stopId);
		}
		else
		{
			return null;
		}
	}

}
