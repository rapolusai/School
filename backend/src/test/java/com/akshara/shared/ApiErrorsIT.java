package com.akshara.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.AccountsFixtures;
import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** Common client mistakes answer the right 4xx as problem details, never the catch-all 500. */
class ApiErrorsIT extends IntegrationTest {

    private Session admin;
    private Session accountant;
    private AccountsFixtures accounts;
    private String rentals;

    @BeforeEach
    void school() throws Exception {
        School school = api.signup();
        admin = api.login(school);
        String email = "meena@" + school.code() + ".akshara.test";
        api.createUser(admin, "Meena Reddy", email, List.of("ACCOUNTANT"));
        accountant = api.login(school.code(), email, TestApi.PASSWORD);
        accounts = new AccountsFixtures(api);
        rentals = accounts.standardHead(accountant, "RENTALS");
    }

    @Test
    void aWrongMethodIs405WithTheAllowedMethods() throws Exception {
        api.post("/api/me", admin.accessToken(), "{}")
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.title").value("Method not allowed"))
                .andExpect(jsonPath("$.type").value("about:blank"));
        api.delete("/api/me", admin.accessToken()).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void aWrongContentTypeIs415() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/accounts/income")
                .header("Authorization", "Bearer " + accountant.accessToken())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("kind=RENTAL"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.title").value("Unsupported content type"));
        // A photo upload sent as JSON instead of multipart.
        api.post("/api/frontoffice/visitors/" + UUID.randomUUID() + "/photo", admin.accessToken(), "{}")
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void aMissingOrMalformedQueryParameterIs400NamingIt() throws Exception {
        api.get("/api/accounts/petty-cash", accountant.accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.from").value("This is required."));
        api.get("/api/accounts/petty-cash?from=yesterday&to=2026-01-31", accountant.accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.from").value("This value is not valid."));
        api.post("/api/accounts/income", accountant.accessToken(), "{not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad request"));
    }

    @Test
    void aMalformedRecordIdInThePathIs404AndARejectedPathIs400NeverA401() throws Exception {
        api.get("/api/accounts/income/abc", accountant.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not found"));
        api.get("/api/accounts/income/zzz'\"<b>", accountant.accessToken())
                .andExpect(status().isNotFound());
        // A path the firewall refuses (";" or an encoded "%") is a 400 problem, not a sign-in error.
        api.get("/api/accounts/income/a;b", accountant.accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad request"))
                .andExpect(jsonPath("$.detail").value("The request could not be read."));
        // A wrong-typed query value still names the parameter.
        api.get("/api/accounts/petty-cash?from=x&to=2026-01-31", accountant.accessToken())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.from").exists());
        // The token is still good.
        api.get("/api/me", accountant.accessToken()).andExpect(status().isOk());
    }

    @Test
    void aFractionSentToAWholeNumberFieldIsRefusedNotTruncated() throws Exception {
        api.post("/api/accounts/income", accountant.accessToken(), """
                {"kind":"RENTAL","headId":"%s","receivedOn":"%s","amountPaise":100.5,"payerName":"Ramesh Gupta",
                 "mode":"CASH","bankAccountId":null,"reference":"UTR123"}""".formatted(rentals, AccountsFixtures.today()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amountPaise").value("Enter a whole number."));
        api.get("/api/accounts/income?from=" + AccountsFixtures.today() + "&to=" + AccountsFixtures.today(),
                accountant.accessToken()).andExpect(jsonPath("$.length()").value(0));
        // Whole numbers still work.
        accounts.income(accountant, rentals, "RENTAL", 100, "CASH", null);
    }

    @Test
    void parallelChangesToTheSameRowAnswer409NotA500() throws Exception {
        String receipt = accounts.income(accountant, rentals, "RENTAL", 120000, "CASH", null);
        int parallel = 6;
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < parallel; i++) {
                Callable<Integer> cancel = () -> {
                    start.await();
                    return api.post("/api/accounts/income/" + receipt + "/cancel", accountant.accessToken(), """
                            {"reason":"Wrong tenant"}""").andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(cancel));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsOnly(200, 409);
            assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
