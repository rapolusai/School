package com.akshara.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.akshara.support.InventoryFixtures;
import com.akshara.support.TestApi;

/** The asset register: tags, depreciation, transfers, disposal with approval, labels and verification rounds. */
class AssetIT extends InventoryIntegrationTest {

    @Test
    void assetsGetTagsAndStraightLineDepreciation() throws Exception {
        InventorySchool s = new InventorySchool();
        LocalDate bought = InventoryFixtures.today().minusYears(1);
        String projector = s.fixtures.asset(s.keeper, "Epson projector", s.stationery, s.store, s.teacherId,
                42_000_00, bought);
        api.get("/api/inventory/assets/" + projector, s.teacher.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.asset.assetTag").value("AST-000001"))
                .andExpect(jsonPath("$.asset.custodianName").value("Rahul Verma"))
                .andExpect(jsonPath("$.asset.locationName").value("Main store"))
                .andExpect(jsonPath("$.asset.depreciationRateBp").value(1500))
                .andExpect(jsonPath("$.asset.depreciationPaise").value(
                        InventoryMath.depreciation(42_000_00, 1500, bought, InventoryFixtures.today())))
                .andExpect(jsonPath("$.asset.status").value("IN_USE"));
        String second = s.fixtures.asset(s.keeper, "Microscope", s.stationery, null, null, 28_500_00, bought);
        api.get("/api/inventory/assets/" + second, s.keeper.accessToken())
                .andExpect(jsonPath("$.asset.assetTag").value("AST-000002"));
        api.get("/api/inventory/assets/by-tag/ast-000002", s.keeper.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.asset.name").value("Microscope"));
        api.get("/api/inventory/assets/by-tag/AST-999999", s.keeper.accessToken()).andExpect(status().isNotFound());
        // Validation: a future purchase, a foreign custodian, a foreign category.
        api.post("/api/inventory/assets", s.keeper.accessToken(), """
                {"name":"X","categoryId":"%s","purchaseDate":"%s","costPaise":100,"condition":"GOOD"}"""
                .formatted(s.stationery, InventoryFixtures.today().plusDays(1))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.purchaseDate").exists());
        InventorySchool other = new InventorySchool();
        api.post("/api/inventory/assets", s.keeper.accessToken(), """
                {"name":"X","categoryId":"%s","custodianId":"%s","purchaseDate":"%s","costPaise":100,
                 "condition":"GOOD"}""".formatted(s.stationery, other.teacherId, bought))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.custodianId").exists());
        api.post("/api/inventory/assets", s.keeper.accessToken(), """
                {"name":"X","categoryId":"%s","purchaseDate":"%s","costPaise":100,"condition":"GOOD"}"""
                .formatted(other.stationery, bought)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.categoryId").exists());
        // Another school: 404 and an empty register; its own tags start again at 1.
        api.get("/api/inventory/assets/" + projector, other.keeper.accessToken()).andExpect(status().isNotFound());
        api.get("/api/inventory/assets", other.keeper.accessToken()).andExpect(jsonPath("$", hasSize(0)));
        String theirs = s.fixtures.asset(other.keeper, "Desk", other.stationery, null, null, 7_000_00, bought);
        api.get("/api/inventory/assets/" + theirs, other.keeper.accessToken())
                .andExpect(jsonPath("$.asset.assetTag").value("AST-000001"));
        // Labels and the register report.
        api.get("/api/inventory/assets/labels", s.keeper.accessToken()).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].assetTag").value("AST-000001"));
        api.get("/api/inventory/assets/labels?ids=" + second, s.keeper.accessToken())
                .andExpect(jsonPath("$", hasSize(1)));
        api.get("/api/inventory/reports/asset-register", s.accountant.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.assets").value(2)).andExpect(jsonPath("$.costPaise").value(70_500_00))
                .andExpect(jsonPath("$.locations", hasSize(2)));
        api.get("/api/inventory/reports/assets-by-custodian", s.keeper.accessToken())
                .andExpect(jsonPath("$.custodians[1].custodianName").value("Rahul Verma"))
                .andExpect(jsonPath("$.custodians[1].assets").value(1));
        String csv = body(api.get("/api/inventory/reports/asset-register.csv", s.keeper.accessToken())
                .andExpect(status().isOk()));
        assertThat(csv).startsWith("﻿Location,Tag").contains("AST-000001,Epson projector,Stationery,Rahul Verma")
                .contains("42000.00");
        assertThat(audit(s.admin, "asset.created")).hasSize(2);
    }

    @Test
    void transfersKeepHistoryAndDisposalNeedsApproval() throws Exception {
        InventorySchool s = new InventorySchool();
        String lab = s.fixtures.location(s.keeper, "Science lab", "LAB");
        String asset = s.fixtures.asset(s.keeper, "Microscope", s.stationery, s.store, null, 28_500_00,
                InventoryFixtures.today().minusMonths(6));
        api.post("/api/inventory/assets/" + asset + "/transfer", s.keeper.accessToken(), """
                {"movedOn":"%s"}""".formatted(InventoryFixtures.today())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.toLocationId").exists());
        api.post("/api/inventory/assets/" + asset + "/transfer", s.keeper.accessToken(), """
                {"toLocationId":"%s","toCustodianId":"%s","reason":"Lab setup","movedOn":"%s"}"""
                .formatted(lab, s.headId, InventoryFixtures.today())).andExpect(status().isOk())
                .andExpect(jsonPath("$.asset.locationName").value("Science lab"))
                .andExpect(jsonPath("$.asset.custodianName").value("Anjali Deshmukh"))
                .andExpect(jsonPath("$.transfers", hasSize(1)))
                .andExpect(jsonPath("$.transfers[0].fromLocationName").value("Main store"))
                .andExpect(jsonPath("$.transfers[0].toCustodianName").value("Anjali Deshmukh"));
        api.post("/api/inventory/assets/" + asset + "/transfer", s.teacher.accessToken(), """
                {"toLocationId":"%s","movedOn":"%s"}""".formatted(s.store, InventoryFixtures.today()))
                .andExpect(status().isForbidden());
        // Disposal: the storekeeper asks with a reason, the principal decides.
        api.post("/api/inventory/assets/" + asset + "/disposal/approve", s.principal.accessToken(), null)
                .andExpect(status().isConflict());
        api.post("/api/inventory/assets/" + asset + "/disposal", s.keeper.accessToken(), """
                {"reason":"Lens cracked beyond repair"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.asset.status").value("DISPOSAL_REQUESTED"));
        api.post("/api/inventory/assets/" + asset + "/disposal/approve", s.keeper.accessToken(), null)
                .andExpect(status().isForbidden());
        api.post("/api/inventory/assets/" + asset + "/disposal/refuse", s.principal.accessToken(), """
                {"reason":"Get a quote for repair first"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.asset.status").value("IN_USE"))
                .andExpect(jsonPath("$.asset.disposalReason").doesNotExist());
        api.post("/api/inventory/assets/" + asset + "/disposal", s.keeper.accessToken(), """
                {"reason":"Repair quote above cost"}""").andExpect(status().isOk());
        api.post("/api/inventory/assets/" + asset + "/disposal/approve", s.principal.accessToken(), """
                {"note":"Agreed"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.asset.status").value("DISPOSED"))
                .andExpect(jsonPath("$.asset.disposedByName").value("Lakshmi Iyer"));
        api.post("/api/inventory/assets/" + asset + "/transfer", s.keeper.accessToken(), """
                {"toLocationId":"%s","movedOn":"%s"}""".formatted(s.store, InventoryFixtures.today()))
                .andExpect(status().isConflict());
        api.get("/api/inventory/assets?status=DISPOSED", s.keeper.accessToken()).andExpect(jsonPath("$", hasSize(1)));
        api.get("/api/inventory/assets/labels", s.keeper.accessToken()).andExpect(jsonPath("$", hasSize(0)));
        assertThat(audit(s.admin, "asset.transferred")).hasSize(1);
        assertThat(audit(s.admin, "asset.disposal_refused")).hasSize(1);
        assertThat(audit(s.admin, "asset.disposed")).hasSize(1);
    }

    @Test
    void verificationRoundsScanOrTickEachAsset() throws Exception {
        InventorySchool s = new InventorySchool();
        String lab = s.fixtures.location(s.keeper, "Science lab", "LAB");
        LocalDate bought = InventoryFixtures.today().minusMonths(3);
        String a = s.fixtures.asset(s.keeper, "Microscope", s.stationery, lab, null, 28_500_00, bought);
        String b = s.fixtures.asset(s.keeper, "Balance", s.stationery, lab, null, 12_800_00, bought);
        String c = s.fixtures.asset(s.keeper, "Printer", s.stationery, s.store, null, 18_900_00, bought);
        api.get("/api/dashboard", s.keeper.accessToken()).andExpect(jsonPath("$.inventory.assetsVerificationDue")
                .value(3)).andExpect(jsonPath("$.inventory.openVerificationRounds").value(0));
        String round = TestApi.read(api.post("/api/inventory/verification-rounds", s.keeper.accessToken(), """
                {"name":"Lab check","locationId":"%s"}""".formatted(lab)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.assets").value(2)).andExpect(jsonPath("$.items", hasSize(2))), "$.id");
        // Scanned by tag, ticked by id, and one the round does not cover.
        api.post("/api/inventory/verification-rounds/" + round + "/verify", s.keeper.accessToken(), """
                {"assetTag":"ast-000001","result":"FOUND"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(1));
        api.post("/api/inventory/verification-rounds/" + round + "/verify", s.keeper.accessToken(), """
                {"assetId":"%s","result":"DAMAGED","note":"Pan bent"}""".formatted(b)).andExpect(status().isOk())
                .andExpect(jsonPath("$.damaged").value(1)).andExpect(jsonPath("$.checked").value(2));
        api.post("/api/inventory/verification-rounds/" + round + "/verify", s.keeper.accessToken(), """
                {"assetId":"%s","result":"FOUND"}""".formatted(c)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.assetId").exists());
        api.post("/api/inventory/verification-rounds/" + round + "/verify", s.keeper.accessToken(), """
                {"assetTag":"AST-424242","result":"FOUND"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.assetTag").exists());
        api.post("/api/inventory/verification-rounds/" + round + "/verify", s.keeper.accessToken(), """
                {"result":"FOUND"}""").andExpect(status().isBadRequest());
        api.get("/api/inventory/assets/" + b, s.keeper.accessToken())
                .andExpect(jsonPath("$.asset.condition").value("DAMAGED"))
                .andExpect(jsonPath("$.asset.lastVerifiedOn").value(InventoryFixtures.today().toString()));
        api.get("/api/inventory/assets/" + a, s.keeper.accessToken())
                .andExpect(jsonPath("$.asset.lastVerifiedOn").value(InventoryFixtures.today().toString()));
        api.get("/api/dashboard", s.keeper.accessToken()).andExpect(jsonPath("$.inventory.assetsVerificationDue")
                .value(1)).andExpect(jsonPath("$.inventory.openVerificationRounds").value(1));
        api.post("/api/inventory/verification-rounds/" + round + "/close", s.principal.accessToken(), null)
                .andExpect(status().isForbidden());
        api.post("/api/inventory/verification-rounds/" + round + "/close", s.keeper.accessToken(), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        api.post("/api/inventory/verification-rounds/" + round + "/verify", s.keeper.accessToken(), """
                {"assetId":"%s","result":"FOUND"}""".formatted(a)).andExpect(status().isConflict());
        api.get("/api/inventory/verification-rounds", s.teacher.accessToken()).andExpect(jsonPath("$", hasSize(1)));
        InventorySchool other = new InventorySchool();
        api.get("/api/inventory/verification-rounds/" + round, other.keeper.accessToken())
                .andExpect(status().isNotFound());
        assertThat(audit(s.admin, "asset.verified")).hasSize(2);
        assertThat(audit(s.admin, "verification_round.closed")).hasSize(1);
    }
}
