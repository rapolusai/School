package com.akshara.transport;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.transport.TransportSchool.Bus;

/** A vehicle's GPS device posting positions with its token, without a sign-in. */
class TransportDeviceIT extends IntegrationTest {

    private ResultActions post(String vehicleId, String token, String ip, double latitude, double longitude)
            throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders
                .post("/api/public/transport/vehicles/" + vehicleId + "/positions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"latitude":%s,"longitude":%s,"accuracyM":8,"speedKmh":24.5}""".formatted(latitude,
                        longitude))
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                });
        if (token != null) {
            request.header(TransportSetupService.DEVICE_HEADER, token);
        }
        return mvc.perform(request);
    }

    private String issue(TransportSchool s, String vehicleId) throws Exception {
        return TestApi.read(api.post("/api/transport/vehicles/" + vehicleId + "/device-token", s.token(), null)
                .andExpect(status().isCreated()), "$.token");
    }

    @Test
    void aDeviceSendsPositionsWithItsVehiclesToken() throws Exception {
        TransportSchool s = new TransportSchool(api);
        Bus bus = s.bus();
        s.allocate(s.asha, bus.route(), bus.stops().get(1), TransportSchool.today());
        String token = issue(s, bus.vehicle());
        String ip = "198.51.100.10";
        double[] gate = TransportSchool.POINTS[0];
        post(bus.vehicle(), null, ip, gate[0], gate[1]).andExpect(status().isUnauthorized());
        post(bus.vehicle(), "akt_wrong", ip, gate[0], gate[1]).andExpect(status().isUnauthorized());
        // Another vehicle's token does not work for this one.
        String van = s.vehicle("TS09 UC 1029", 12, null);
        String vanToken = issue(s, van);
        post(bus.vehicle(), vanToken, ip, gate[0], gate[1]).andExpect(status().isUnauthorized());
        post(bus.vehicle(), token, ip, 95, gate[1]).andExpect(status().isBadRequest());
        // No trip running: the vehicle keeps its latest position.
        post(bus.vehicle(), token, ip, gate[0], gate[1]).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.onTrip").value(false));
        api.get("/api/transport/vehicles/" + bus.vehicle(), s.token())
                .andExpect(jsonPath("$.lastPositionAt").exists());
        // On a running trip, the position moves the trip along like the phone's.
        String trip = TestApi.read(api.post("/api/transport/routes/" + bus.route() + "/trips",
                s.attendant.accessToken(), """
                {"kind":"MORNING"}""").andExpect(status().isCreated()), "$.id");
        post(bus.vehicle(), " " + token + " ", ip, gate[0], gate[1]).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.onTrip").value(true));
        api.get("/api/transport/trips/" + trip, s.attendant.accessToken())
                .andExpect(jsonPath("$.position.source").value("DEVICE"))
                .andExpect(jsonPath("$.position.accuracyM").value(8))
                .andExpect(jsonPath("$.reachedStop.name").value("Gate Stop"));
        // A position for another school's vehicle with this token is refused.
        TransportSchool other = new TransportSchool(api);
        String theirs = other.vehicle("TS10 AA 0001", 30, null);
        post(theirs, token, ip, gate[0], gate[1]).andExpect(status().isUnauthorized());
        // A revoked token stops working at once.
        api.delete("/api/transport/vehicles/" + bus.vehicle() + "/device-token", s.token())
                .andExpect(status().isNoContent());
        post(bus.vehicle(), token, ip, gate[0], gate[1]).andExpect(status().isUnauthorized());
        // A new token replaces it.
        String next = issue(s, bus.vehicle());
        post(bus.vehicle(), next, ip, gate[0], gate[1]).andExpect(status().isAccepted());
        post(bus.vehicle(), token, ip, gate[0], gate[1]).andExpect(status().isUnauthorized());
    }

    @Test
    void devicesAreRateLimited() throws Exception {
        TransportSchool s = new TransportSchool(api);
        String bus = s.vehicle("TS09 UB 4410", 40, null);
        String token = issue(s, bus);
        // At most 12 positions a minute from one vehicle.
        for (int i = 0; i < 12; i++) {
            post(bus, token, "198.51.100.20", 17.41, 78.44).andExpect(status().isAccepted());
        }
        post(bus, token, "198.51.100.20", 17.41, 78.44).andExpect(status().isTooManyRequests());
        // Guessing tokens from one address is stopped after 20 wrong ones, even with a right one afterwards.
        String ip = "203.0.113.7";
        for (int i = 0; i < 20; i++) {
            post(bus, "akt_guess" + i, ip, 17.41, 78.44).andExpect(status().isUnauthorized());
        }
        post(bus, "akt_guess", ip, 17.41, 78.44).andExpect(status().isTooManyRequests());
        String other = s.vehicle("TS09 UB 4411", 40, null);
        post(other, issue(s, other), ip, 17.41, 78.44).andExpect(status().isTooManyRequests());
        // Other addresses are not affected.
        post(other, issue(s, other), "203.0.113.8", 17.41, 78.44).andExpect(status().isAccepted());
    }
}
