package com.example.travel.service;

import com.example.travel.model.HotelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "travel.mcp.client", name = "enabled", havingValue = "true")
public class McpHotelSearchClient {

    private static final Logger log = LoggerFactory.getLogger(McpHotelSearchClient.class);

    private final McpToolClient client;

    public McpHotelSearchClient(McpToolClient client) {
        this.client = client;
    }

    public List<HotelOption> search(String destination, String travelStyle, boolean cheaper) {
        return search(destination, travelStyle, cheaper, null, null, 2, 0, null);
    }

    public List<HotelOption> search(String destination, String travelStyle, boolean cheaper, java.math.BigDecimal hotelBudget) {
        return search(destination, travelStyle, cheaper, null, null, 2, 0, hotelBudget);
    }

    public List<HotelOption> search(String destination, String travelStyle, boolean cheaper,
                                    java.time.LocalDate checkIn, java.time.LocalDate checkOut,
                                    int adults, int children, java.math.BigDecimal hotelBudget) {
        try {
            String budgetText = hotelBudget == null ? "" : ", maximum hotel price per night INR=" + hotelBudget.toPlainString();
            Map<String, Object> arguments = new java.util.LinkedHashMap<>();
            arguments.put("destination", destination == null ? "" : destination);
            arguments.put("travelStyle", travelStyle == null ? "balanced" : travelStyle);
            arguments.put("cheaper", cheaper);
            if (checkIn != null) arguments.put("checkInDate", checkIn.toString());
            if (checkOut != null) arguments.put("checkOutDate", checkOut.toString());
            arguments.put("adults", Math.max(1, adults));
            arguments.put("children", Math.max(0, children));
            if (hotelBudget != null) {
                arguments.put("maxPricePerNight", hotelBudget);
            }
            String userInput = "Find real, identifiable hotel properties for destination "
                    + (destination == null ? "" : destination)
                    + ", travel style " + (travelStyle == null ? "balanced" : travelStyle)
                    + ", cheaper=" + cheaper + budgetText
                    + ". Return hotel properties only; do not return article titles, listicles, guides or generic research headings.";
            JsonNode root = client.invokePreferred("search_hotels", "Find accommodation/hotels", userInput, arguments);
            log.debug("mcp.hotel.raw-response destination={} payload={}", destination, abbreviate(root == null ? "" : root.toString()));
            List<HotelOption> hotels = new ArrayList<>();
            List<JsonNode> records = hotelRecords(root);
            if (records.isEmpty()) {
                log.warn("mcp.hotel.no-structured-records destination={} responseKeys={}", destination, root == null ? "null" : root.toString());
            }
            for (JsonNode node : records) {
                HotelOption hotel = new HotelOption();
                hotel.setName(node.path("name").asString(""));
                hotel.setArea(node.path("area").asString(""));
                hotel.setPriceRange(node.path("priceRange").asString(""));
                hotel.setRating(node.path("rating").asString(""));
                hotel.setSuitableFor(node.path("suitableFor").asString(""));
                hotel.setNotes(node.path("notes").asString(""));
                hotel.setImageUrl(node.path("imageUrl").asString(""));
                hotel.setBookingUrl(node.path("bookingUrl").asString(""));
                hotel.setAmenities(node.path("amenities").asString(""));
                hotel.setHotelClass(node.path("hotelClass").asString(""));
                hotel.setReviews(node.path("reviews").asInt(0));
                hotel.setTotalPrice(node.path("totalPrice").asString(""));
                hotel.setCurrency(node.path("currency").asString(""));
                hotel.setDeal(node.path("deal").asString(""));
                hotel.setFreeCancellation(node.path("freeCancellation").asBoolean(false));
                hotel.setPropertyToken(node.path("propertyToken").asString(""));
                hotel.setProvider(node.path("provider").asString(""));
                hotels.add(hotel);
                log.debug("mcp.hotel.raw-record destination={} name={} area={} priceRange={}",
                        destination, abbreviate(hotel.getName()), abbreviate(hotel.getArea()),
                        abbreviate(hotel.getPriceRange()));
            }
            return hotels;
        } catch (Exception exception) {
            String message = safeMessage(exception);
            boolean protocolFailure = isProtocolFailure(exception);
            if (protocolFailure) {
                // The MCP server has returned a malformed/truncated JSON-RPC frame.
                // Do not leak the giant provider payload into the application log and
                // do not retry it here; HotelAgentService will use its independent
                // research fallback.
                log.warn("mcp.hotel.protocol-failure destination={} cheaper={} action=fallback reason={}",
                        destination, cheaper, abbreviate(message));
            } else {
                log.warn("mcp.hotel.provider-failure destination={} cheaper={} action=fallback errorType={} reason={}",
                        destination, cheaper, exception.getClass().getSimpleName(), abbreviate(message));
            }
            return List.of();
        }
    }

    private List<JsonNode> hotelRecords(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return List.of();
        }
        JsonNode candidates = root.path("hotels");
        if (!candidates.isArray()) candidates = root.path("results");
        if (!candidates.isArray()) candidates = root.path("data").path("hotels");
        if (!candidates.isArray()) candidates = root.path("data").path("results");
        if (!candidates.isArray()) return List.of();
        List<JsonNode> result = new ArrayList<>();
        candidates.forEach(result::add);
        return result;
    }


    private boolean isProtocolFailure(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("error parsing json-rpc message")
                        || normalized.contains("unexpected end-of-input")
                        || normalized.contains("failed to read value")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String abbreviate(String value) {
        if (value == null) return "";
        String normalized = value.replace("\n", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
