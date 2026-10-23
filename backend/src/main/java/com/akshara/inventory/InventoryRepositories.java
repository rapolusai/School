package com.akshara.inventory;

/*
 * The inventory module's Spring Data repositories, one interface per entity, in one file because each is a few
 * queries; all package-private.
 */

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface PurchaseApproverRepository extends JpaRepository<PurchaseApprover, UUID> {

    @Query("select a from PurchaseApprover a order by a.limitPaise nulls last, a.userName")
    List<PurchaseApprover> findAllOrdered();

    Optional<PurchaseApprover> findByUserId(UUID userId);
}

interface CategoryRepository extends JpaRepository<Category, UUID> {

    @Query("select c from Category c order by lower(c.name)")
    List<Category> findAllOrdered();

    @Query("select count(c) > 0 from Category c where lower(c.name) = lower(?1) and c.id <> ?2")
    boolean nameTaken(String name, UUID exceptId);
}

interface LocationRepository extends JpaRepository<Location, UUID> {

    @Query("select l from Location l order by lower(l.name)")
    List<Location> findAllOrdered();

    @Query("select count(l) > 0 from Location l where lower(l.name) = lower(?1) and l.id <> ?2")
    boolean nameTaken(String name, UUID exceptId);
}

interface ItemRepository extends JpaRepository<Item, UUID> {

    @Query("select i from Item i order by lower(i.name)")
    List<Item> findAllOrdered();

    @Query("select count(i) > 0 from Item i where lower(i.name) = lower(?1) and i.id <> ?2")
    boolean nameTaken(String name, UUID exceptId);

    @Query("select count(i) > 0 from Item i where i.categoryId = ?1")
    boolean anyInCategory(UUID categoryId);
}

interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    @Query("select m from StockMovement m order by m.movedOn, m.createdAt")
    List<StockMovement> findAllOrdered();

    @Query("select m from StockMovement m where m.itemId = ?1 order by m.movedOn, m.createdAt")
    List<StockMovement> findByItem(UUID itemId);

    @Query("select m from StockMovement m where m.movedOn between ?1 and ?2 order by m.movedOn, m.createdAt")
    List<StockMovement> findBetween(LocalDate from, LocalDate to);

    @Query("select count(m) > 0 from StockMovement m where m.itemId = ?1")
    boolean anyForItem(UUID itemId);

    @Query("select count(m) > 0 from StockMovement m where m.locationId = ?1")
    boolean anyAtLocation(UUID locationId);
}

interface StockTakeRepository extends JpaRepository<StockTake, UUID> {

    @Query("select s from StockTake s order by s.startedOn desc, s.createdAt desc")
    List<StockTake> findAllNewestFirst();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockTake s where s.id = ?1")
    Optional<StockTake> findForUpdate(UUID id);
}

interface StockTakeLineRepository extends JpaRepository<StockTakeLine, UUID> {

    @Query("select l from StockTakeLine l where l.stockTakeId = ?1")
    List<StockTakeLine> findByTake(UUID stockTakeId);
}

interface AssetRepository extends JpaRepository<Asset, UUID> {

    @Query("select a from Asset a order by a.assetTag")
    List<Asset> findAllOrdered();

    @Query("select a from Asset a where a.status <> com.akshara.inventory.AssetStatus.DISPOSED order by a.assetTag")
    List<Asset> findLive();

    Optional<Asset> findByAssetTag(String assetTag);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Asset a where a.id = ?1")
    Optional<Asset> findForUpdate(UUID id);

    @Query("select count(a) > 0 from Asset a where a.categoryId = ?1")
    boolean anyInCategory(UUID categoryId);

    @Query("select count(a) > 0 from Asset a where a.locationId = ?1")
    boolean anyAtLocation(UUID locationId);
}

interface AssetTransferRepository extends JpaRepository<AssetTransfer, UUID> {

    @Query("select t from AssetTransfer t where t.assetId = ?1 order by t.movedOn desc, t.createdAt desc")
    List<AssetTransfer> findByAsset(UUID assetId);
}

interface VerificationRoundRepository extends JpaRepository<VerificationRound, UUID> {

    @Query("select r from VerificationRound r order by r.startedOn desc, r.createdAt desc")
    List<VerificationRound> findAllNewestFirst();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from VerificationRound r where r.id = ?1")
    Optional<VerificationRound> findForUpdate(UUID id);

    @Query("select count(r) from VerificationRound r where r.status = com.akshara.inventory.StockTakeStatus.OPEN")
    long countOpen();
}

interface VerificationItemRepository extends JpaRepository<VerificationItem, UUID> {

    @Query("select i from VerificationItem i where i.roundId = ?1")
    List<VerificationItem> findByRound(UUID roundId);

    Optional<VerificationItem> findByRoundIdAndAssetId(UUID roundId, UUID assetId);
}

interface RequisitionRepository extends JpaRepository<Requisition, UUID> {

    @Query("select r from Requisition r order by r.createdAt desc")
    List<Requisition> findAllNewestFirst();

    @Query("select r from Requisition r where r.requestedById = ?1 order by r.createdAt desc")
    List<Requisition> findByRequester(UUID userId);

    @Query("select r from Requisition r where r.status in (com.akshara.inventory.RequisitionStatus.SUBMITTED, "
            + "com.akshara.inventory.RequisitionStatus.HEAD_APPROVED) order by r.submittedAt")
    List<Requisition> findWaiting();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Requisition r where r.id = ?1")
    Optional<Requisition> findForUpdate(UUID id);
}

interface RequisitionLineRepository extends JpaRepository<RequisitionLine, UUID> {

    @Query("select l from RequisitionLine l where l.requisitionId = ?1 order by l.lineNo")
    List<RequisitionLine> findByRequisition(UUID requisitionId);

    @Query("select l from RequisitionLine l where l.requisitionId in ?1 order by l.lineNo")
    List<RequisitionLine> findByRequisitions(List<UUID> requisitionIds);
}

interface PurchaseApprovalRepository extends JpaRepository<PurchaseApproval, UUID> {

    @Query("select a from PurchaseApproval a where a.requisitionId = ?1 order by a.createdAt")
    List<PurchaseApproval> findByRequisition(UUID requisitionId);

    @Query("select a from PurchaseApproval a where a.purchaseOrderId = ?1 order by a.createdAt")
    List<PurchaseApproval> findByOrder(UUID purchaseOrderId);
}

interface RfqRepository extends JpaRepository<Rfq, UUID> {

    @Query("select r from Rfq r order by r.createdAt desc")
    List<Rfq> findAllNewestFirst();
}

interface QuoteRepository extends JpaRepository<Quote, UUID> {

    @Query("select q from Quote q where q.rfqId = ?1 order by q.amountPaise, q.createdAt")
    List<Quote> findByRfq(UUID rfqId);
}

interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    @Query("select p from PurchaseOrder p order by p.createdAt desc")
    List<PurchaseOrder> findAllNewestFirst();

    @Query("select p from PurchaseOrder p where p.status = com.akshara.inventory.PurchaseOrderStatus.SUBMITTED "
            + "order by p.submittedAt")
    List<PurchaseOrder> findSubmitted();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PurchaseOrder p where p.id = ?1")
    Optional<PurchaseOrder> findForUpdate(UUID id);

    @Query("select count(p) > 0 from PurchaseOrder p where p.vendorId = ?1")
    boolean anyForVendor(UUID vendorId);
}

interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, UUID> {

    @Query("select l from PurchaseOrderLine l where l.purchaseOrderId = ?1 order by l.lineNo")
    List<PurchaseOrderLine> findByOrder(UUID purchaseOrderId);

    @Query("select l from PurchaseOrderLine l where l.purchaseOrderId in ?1 order by l.lineNo")
    List<PurchaseOrderLine> findByOrders(List<UUID> purchaseOrderIds);
}

interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, UUID> {

    @Query("select g from GoodsReceipt g where g.purchaseOrderId = ?1 order by g.receivedOn, g.createdAt")
    List<GoodsReceipt> findByOrder(UUID purchaseOrderId);
}

interface GoodsReceiptLineRepository extends JpaRepository<GoodsReceiptLine, UUID> {

    @Query("select l from GoodsReceiptLine l where l.goodsReceiptId = ?1")
    List<GoodsReceiptLine> findByReceipt(UUID goodsReceiptId);
}
