package com.queuesense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:qstest;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ApiTest {
    @Autowired MockMvc mvc;

    private int send(String path, String json) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void crowdLevelThresholds() {
        assertEquals("low", QueueService.level(0.1));
        assertEquals("medium", QueueService.level(0.5));
        assertEquals("high", QueueService.level(0.9));
    }

    @Test
    void checkInOutFlow() throws Exception {
        String ok = "{\"venue_id\":1,\"device_hash\":\"t1\",\"name\":\"Test\",\"hours\":1}";
        assertEquals(422, send("/checkin", "{\"venue_id\":1,\"device_hash\":\"t1\",\"name\":\" \",\"hours\":1}"));
        assertEquals(200, send("/checkin", ok));
        assertEquals(409, send("/checkin", ok));
        assertEquals(200, send("/checkout", ok));
        assertEquals(404, send("/checkout", ok));
        mvc.perform(get("/admin/stats")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/stats").header("x-admin-key", "admin123")).andExpect(status().isOk());
    }
}