package com.project.trip4u.logic.actionUtils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;

import com.project.trip4u.algorithm.Algorithm;
import com.project.trip4u.boundary.ActionBoundary;
import com.project.trip4u.boundaryUtils.EventInfo;
import com.project.trip4u.boundaryUtils.TripInfo;
import com.project.trip4u.converter.AttributeConverter;
import com.project.trip4u.converter.JsonConverter;
import com.project.trip4u.dao.TripDao;
import com.project.trip4u.data.ActionType;
import com.project.trip4u.exception.NotFoundException;
import com.project.trip4u.utils.Consts;
import com.project.trip4u.utils.Credentials;

import org.springframework.web.client.RestTemplate;

@Component
public class ClientActions {

	private static TripDao tripDao;
	private static AttributeConverter attributeConverter;
	private static JsonConverter jsonConverter;

	@Autowired
	public void setTripDao(TripDao tripDao) {
		ClientActions.tripDao = tripDao;
	}

	@Autowired
	public void setAttributeConverter(AttributeConverter attributeConverter) {
		ClientActions.attributeConverter = attributeConverter;
	}

	@Autowired
	public void setJsonConverter(JsonConverter jsonConverter) {
		ClientActions.jsonConverter = jsonConverter;
	}
	

	public static Object actionInvoker(ActionBoundary action) throws ParseException {

		ActionType type = action.getType();
		Map<String, Object> map = new HashMap<>(); // Optional for Get actions

		switch (type) {
		case CREATE:
			map.put("trip", generateTrip(action));
			tripDao.save((TripInfo)map.get("trip"));
			break;
			
		case EDIT:
			map.put("trip", generateTrip(action));
			tripDao.save((TripInfo)map.get("trip"));
			break;
			
		case GENERATE:
			map.put("trip", generateTrip(action));
			tripDao.save((TripInfo)map.get("trip"));
			break;

		case DELETE:
			deleteTrip(action);
			break;

		case UPDATE:
			UpdateTrip(action);
			break;

		default:
			new NotFoundException("Action Type Not Valid");
		}

		return map;
	}

	private static void UpdateTrip(ActionBoundary action) {
		TripInfo tripInfo = tripDao.findByTripId(action.getElementId())
				.orElseThrow(() -> new NotFoundException("Element With This Id Not Exist"));
		TripInfo updateTripInfo = attributeConverter.toAttribute(action.getMoreDetails().get("trip"), TripInfo.class);
		updateTripInfo.setTripId(action.getElementId());
		updateTripInfo.setUserId(action.getInvokeBy());
		tripDao.save(updateTripInfo);
	}

	private static void deleteTrip(ActionBoundary action) {
		TripInfo tripInfo = tripDao.findByTripId(action.getElementId())
				.orElseThrow(() -> new NotFoundException("Element With This Id Not Exist"));
		tripDao.deleteById(tripInfo.getTripId());
	}

	private static TripInfo generateTrip(ActionBoundary action) throws ParseException {

		ArrayList<EventInfo> allEvents = new ArrayList<>();
		
		Map<String, String> categories = new HashMap<String, String>() {{
			put("hiking", "poitype-Alpine_hut|camping|daytrips|poitype-Forest|hiking|poitype-Hiking_trail|wildlife|national_park|poitype-Sight|sightseeing");
			put("extreme", "adrenaline|amusementparks");
			put("culture", "art|culture|history|poitype-Memorial|character-World_heritage");
			put("shopping", "shopping|poitype-Shopping_centre|poitype-Shopping_district");
			put("museums", "museums");
			put("food", "cuisine|food|foodexperiences|poitype-Restaurant");
			put("relaxing", "beaches|poitype-Botanical_garden|poitype-Hot_spring|poitype-Spa");
			put("casino", "poitype-Casino|gambling");
		}};
		
		TripInfo trip = attributeConverter.toAttribute(action.getMoreDetails().get("trip"), TripInfo.class);
		
		trip.setTripId(action.getElementId());
		trip.setUserId(action.getInvokeBy());

		int tripDays = getDifferenseBetweenDates(trip.getStartDate(), trip.getEndDate());
		trip.setLength(tripDays);
		
		int numOfEvents = (int) Math.ceil((tripDays * trip.getDayLoad().getValue()) / trip.getCategories().size());;
		if(action.getType().toString().equals("EDIT") || action.getType().toString().equals("CREATE")) {
			numOfEvents *= 2;
		} 
		
		HttpHeaders headers = new HttpHeaders();
		headers.setAccept(Collections.singletonList(new MediaType("application", "json")));
		headers.add("X-Triposo-Account", Credentials.TRIPOSO_ACCOUNT);
		headers.add("X-Triposo-Token", Credentials.TRIPOSO_TOKEN);

		HttpEntity<?> httpEntity = new HttpEntity<Object>(headers);

		RestTemplate restTemplate = new RestTemplate();
		restTemplate.getMessageConverters().add(new MappingJackson2HttpMessageConverter());
		
		for (String category : trip.getCategories()) {
			String query = String.format(
					Consts.BASE_TRIPOSO_URL 
					+ "annotate=distance:linestring:%s,%s" 
					+ "&tag_labels=%s" 
					+ "&distance=<10000"
					+ "&order_by=-score" 
					+ "&count=%d"
					+ "&fields=name,coordinates,intro,snippet,images,properties,score",
					trip.getStartLocation(), trip.getEndLocation(), categories.get(category), numOfEvents);

			ResponseEntity<String> response = restTemplate.exchange(query, HttpMethod.GET, httpEntity, String.class);
			JSONObject events = new JSONObject(response.getBody());
			JSONArray results = events.getJSONArray("results");
			for (int i = 0; i < results.length(); i++) {
				EventInfo newEvent = jsonConverter.toEventInfo(results.getJSONObject(i), category);
				if(findEventIndex(allEvents, newEvent) == -1) {
					allEvents.add(newEvent);
				}
			}
		}
		
		int numOfTotalEvents = tripDays * trip.getDayLoad().getValue();
		if(action.getType().toString().equals("EDIT") || action.getType().toString().equals("CREATE")) {
			numOfTotalEvents *= 2;
		} 
		if(allEvents.size() < numOfTotalEvents) {
			String query = String.format(
					Consts.BASE_TRIPOSO_URL 
					+ "annotate=distance:linestring:%s,%s" 
					+ "&tag_labels=character-Popular_with_locals" 
					+ "&distance=<10000"
					+ "&order_by=-character-Popular_with_locals_score" 
					+ "&count=%d"
					+ "&fields=name,coordinates,intro,snippet,images,properties,score",
					trip.getStartLocation(), trip.getEndLocation(), numOfTotalEvents - allEvents.size());
			ResponseEntity<String> response = restTemplate.exchange(query, HttpMethod.GET, httpEntity, String.class);
			JSONObject events = new JSONObject(response.getBody());
			JSONArray results = events.getJSONArray("results");
			for (int i = 0; i < results.length(); i++) {
				allEvents.add(jsonConverter.toEventInfo(results.getJSONObject(i), "hidden gems"));
			}
		}
		
		if(action.getType().toString().equals("EDIT") || action.getType().toString().equals("CREATE")) {
			trip.setEventsPool(allEvents);
			return trip;
		} 
		return Algorithm.generateTrip(allEvents, trip);
	}

	public static int getDifferenseBetweenDates(String start, String end) throws ParseException {

		SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy", Locale.ENGLISH);
		Date firstDate = sdf.parse(start);
		Date secondDate = sdf.parse(end);

		long diffInMillies = Math.abs(secondDate.getTime() - firstDate.getTime());
		int days = (int) TimeUnit.DAYS.convert(diffInMillies, TimeUnit.MILLISECONDS) + 1;

		return days;
	}
	
	// Function that finds the events index of specific event in the events array.
		private static int findEventIndex(ArrayList<EventInfo> allEvents, EventInfo event) {
			for (int i = 0; i < allEvents.size(); i++) {
				if (allEvents.get(i).getName().equals(event.getName())) {
					return i;
				}
			}
			return -1;
		}

}
