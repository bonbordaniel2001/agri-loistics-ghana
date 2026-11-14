package com.agri.escrow.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class AgriPlatformController {

    @Autowired
    private JdbcTemplate jdbc;

    // 1. Health Check (Used by UptimeRobot to prevent Render sleep)
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ONLINE", "currency", "GHS");
    }

    // 2. Sign In or Register User
    @PostMapping("/auth")
    public Map<String, Object> authenticate(@RequestBody Map<String, String> payload) {
        String phone = payload.get("phone");
        String fullName = payload.getOrDefault("fullName", "Ghana Agri User");
        String role = payload.get("role");

        List> existing = jdbc.queryForList("SELECT * FROM users WHERE phone = ?", phone);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        jdbc.update("INSERT INTO users (full_name, phone, role) VALUES (?, ?, ?)", fullName, phone, role);
        return jdbc.queryForList("SELECT * FROM users WHERE phone = ?", phone).get(0);
    }

    // 3. Get System Petrol Rate & Update Petrol Rate (Admin)
    @GetMapping("/petrol-rate")
    public Map<String, Object> getPetrolRate() {
        return jdbc.queryForMap("SELECT setting_value AS petrolPrice FROM system_settings WHERE setting_key = 'PETROL_PRICE_GHS'");
    }

    @PostMapping("/petrol-rate")
    public Map<String, Object> updatePetrolRate(@RequestBody Map<String, Object> payload) {
        BigDecimal newRate = new BigDecimal(payload.get("petrolPrice").toString());
        jdbc.update("UPDATE system_settings SET setting_value = ? WHERE setting_key = 'PETROL_PRICE_GHS'", newRate);
        return Map.of("message", "Petrol price updated", "petrolPrice", newRate);
    }

    // 4. Create Commodity Listing with Dynamic Pricing (Crops & Livestock)
    @PostMapping("/commodities")
    public Map<String, Object> addCommodity(@RequestBody Map<String, Object> p) {
        int sellerId = Integer.parseInt(p.get("sellerId").toString());
        String title = p.get("title").toString();
        String category = p.get("category").toString();
        String localUnit = p.get("localUnit").toString();
        int quantity = Integer.parseInt(p.get("quantity").toString());
        double basePrice = Double.parseDouble(p.get("basePrice").toString());
        String qualityGrade = p.get("qualityGrade").toString();
        double demandIndex = Double.parseDouble(p.getOrDefault("demandIndex", 12.0).toString());
        double supplyIndex = Double.parseDouble(p.getOrDefault("supplyIndex", 10.0).toString());
        String imageUrl = p.get("imageUrl").toString();

        double qFactor = switch (qualityGrade) {
            case "GRADE_A" -> 1.15;
            case "GRADE_B" -> 1.00;
            case "GRADE_C" -> 0.80;
            default -> 1.00;
        };

        double ratio = supplyIndex > 0 ? (1.0 + (demandIndex - supplyIndex) / supplyIndex) : 1.0;
        BigDecimal finalUnitPrice = BigDecimal.valueOf(basePrice * qFactor * ratio).setScale(2, RoundingMode.HALF_UP);

        jdbc.update("""
            INSERT INTO commodities (seller_id, title, category, local_unit, quantity, base_price, quality_grade, demand_index, supply_index, final_unit_price, image_url)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, sellerId, title, category, localUnit, quantity, basePrice, qualityGrade, demandIndex, supplyIndex, finalUnitPrice, imageUrl);

        return Map.of("status", "LISTED", "finalUnitPrice", finalUnitPrice);
    }

    // 5. Fetch Active Marketplace Listings
    @GetMapping("/commodities")
    public List> listCommodities() {
        return jdbc.queryForList("""
            SELECT c.*, u.full_name AS seller_name, u.trust_score AS seller_trust
            FROM commodities c
            JOIN users u ON c.seller_id = u.user_id
            WHERE c.is_available = TRUE AND c.quantity > 0
            ORDER BY u.trust_score DESC, c.created_at DESC
        """);
    }

    // 6. Place Order & Lock Buyer Funds in Escrow
    @PostMapping("/orders")
    @Transactional
    public Map<String, Object> createEscrowOrder(@RequestBody Map<String, Object> p) {
        int buyerId = Integer.parseInt(p.get("buyerId").toString());
        int commodityId = Integer.parseInt(p.get("commodityId").toString());
        int qty = Integer.parseInt(p.get("quantityOrdered").toString());
        String vehicleType = p.get("vehicleType").toString();
        double distanceKm = Double.parseDouble(p.get("distanceKm").toString());

        Map<String, Object> item = jdbc.queryForMap("SELECT * FROM commodities WHERE commodity_id = ?", commodityId);
        BigDecimal unitPrice = (BigDecimal) item.get("final_unit_price");
        BigDecimal productCost = unitPrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);

        BigDecimal petrolPrice = (BigDecimal) jdbc.queryForMap(
            "SELECT setting_value FROM system_settings WHERE setting_key = 'PETROL_PRICE_GHS'"
        ).get("setting_value");

        double baseRate = vehicleType.equals("KIA_TRUCK") ? 150.0 : 40.0;
        double burnRate = vehicleType.equals("KIA_TRUCK") ? 0.25 : 0.08;
        BigDecimal transportFee = BigDecimal.valueOf(baseRate + (distanceKm * burnRate * petrolPrice.doubleValue()))
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal totalEscrow = productCost.add(transportFee);

        // Deduct from Buyer Wallet into Escrow Hold
        jdbc.update("UPDATE users SET wallet_balance = wallet_balance - ? WHERE user_id = ?", totalEscrow, buyerId);
        jdbc.update("UPDATE commodities SET quantity = quantity - ? WHERE commodity_id = ?", qty, commodityId);

        jdbc.update("""
            INSERT INTO orders (buyer_id, commodity_id, quantity_ordered, product_cost, transport_fee, vehicle_type, distance_km, escrow_status)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'ESCROW_LOCKED')
        """, buyerId, commodityId, qty, productCost, transportFee, vehicleType, distanceKm);

        return Map.of("status", "ESCROW_LOCKED", "productCost", productCost, "transportFee", transportFee, "totalEscrow", totalEscrow);
    }

    // 7. Fetch All Orders with Full Actor Details
    @GetMapping("/orders")
    public List> getAllOrders() {
        return jdbc.queryForList("""
            SELECT o.*, c.title AS commodity_title, c.image_url AS listing_photo, c.seller_id,
                   s.full_name AS seller_name, b.full_name AS buyer_name, d.full_name AS driver_name
            FROM orders o
            JOIN commodities c ON o.commodity_id = c.commodity_id
            JOIN users s ON c.seller_id = s.user_id
            JOIN users b ON o.buyer_id = b.user_id
            LEFT JOIN users d ON o.driver_id = d.user_id
            ORDER BY o.order_id DESC
        """);
    }

    // 8. Driver Farm-Gate Verification & Collateral Freeze (Liability Transfers to Driver)
    @PostMapping("/orders/{orderId}/driver-verify")
    @Transactional
    public Map<String, Object> driverVerifyPickup(@PathVariable int orderId, @RequestBody Map<String, Object> p) {
        int driverId = Integer.parseInt(p.get("driverId").toString());
        String pickupPhotoUrl = p.get("pickupPhotoUrl").toString();

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE order_id = ?", orderId);
        BigDecimal productCost = (BigDecimal) order.get("product_cost");

        // Freeze collateral equal to cargo value from Driver's collateral balance
        jdbc.update("UPDATE users SET collateral_balance = collateral_balance - ? WHERE user_id = ?", productCost, driverId);
        jdbc.update("""
            UPDATE orders SET driver_id = ?, pickup_photo_url = ?, escrow_status = 'IN_TRANSIT_VERIFIED'
            WHERE order_id = ?
        """, driverId, pickupPhotoUrl, orderId);

        return Map.of("status", "IN_TRANSIT_VERIFIED", "collateralFrozen", productCost);
    }

    // 9. Update Live Driver GPS Telemetry
    @PostMapping("/orders/{orderId}/gps")
    public Map<String, Object> updateGps(@PathVariable int orderId, @RequestBody Map<String, Object> p) {
        double lat = Double.parseDouble(p.get("lat").toString());
        double lng = Double.parseDouble(p.get("lng").toString());
        jdbc.update("UPDATE orders SET driver_lat = ?, driver_lng = ? WHERE order_id = ?", lat, lng, orderId);
        return Map.of("status", "GPS_UPDATED", "lat", lat, "lng", lng);
    }

    // 10. Buyer Confirms Good Delivery -> Release Escrow + Add Trust & Loyalty Points
    @PostMapping("/orders/{orderId}/confirm-delivery")
    @Transactional
    public Map<String, Object> confirmDelivery(@PathVariable int orderId) {
        Map<String, Object> order = jdbc.queryForMap("""
            SELECT o.*, c.seller_id FROM orders o JOIN commodities c ON o.commodity_id = c.commodity_id WHERE o.order_id = ?
        """, orderId);

        BigDecimal productCost = (BigDecimal) order.get("product_cost");
        BigDecimal transportFee = (BigDecimal) order.get("transport_fee");
        int sellerId = (int) order.get("seller_id");
        int buyerId = (int) order.get("buyer_id");
        Integer driverId = (Integer) order.get("driver_id");

        // Pay Seller & Reward +5 Trust +20 Loyalty
        jdbc.update("UPDATE users SET wallet_balance = wallet_balance + ?, trust_score = trust_score + 5, loyalty_points = loyalty_points + 20 WHERE user_id = ?", productCost, sellerId);

        // Pay Driver, Release Collateral & Reward +5 Trust +20 Loyalty
        if (driverId != null) {
            jdbc.update("UPDATE users SET wallet_balance = wallet_balance + ?, collateral_balance = collateral_balance + ?, trust_score = trust_score + 5, loyalty_points = loyalty_points + 20 WHERE user_id = ?",
                    transportFee, productCost, driverId);
        }

        // Reward Buyer Loyalty Points
        jdbc.update("UPDATE users SET loyalty_points = loyalty_points + 25 WHERE user_id = ?", buyerId);

        jdbc.update("UPDATE orders SET escrow_status = 'COMPLETED_SETTLED' WHERE order_id = ?", orderId);
        return Map.of("status", "COMPLETED_SETTLED");
    }

    // 11. Buyer Raises Dispute (Bad Produce / Damaged Livestock)
    @PostMapping("/orders/{orderId}/dispute")
    @Transactional
    public Map<String, Object> raiseDispute(@PathVariable int orderId, @RequestBody Map<String, String> p) {
        String reason = p.get("reason");
        String rejectionPhotoUrl = p.getOrDefault("rejectionPhotoUrl", "");

        jdbc.update("UPDATE orders SET escrow_status = 'DISPUTED_FROZEN', rejection_photo_url = ? WHERE order_id = ?", rejectionPhotoUrl, orderId);
        jdbc.update("INSERT INTO disputes (order_id, reason) VALUES (?, ?)", orderId, reason);
        return Map.of("status", "DISPUTED_FROZEN");
    }

    // 12. Admin Resolves Dispute (Driver Guilty -> Money Paid to Seller + Refund Buyer)
    @PostMapping("/disputes/{orderId}/resolve")
    @Transactional
    public Map<String, Object> resolveDispute(@PathVariable int orderId, @RequestBody Map<String, String> p) {
        String verdict = p.get("verdict"); // DRIVER_GUILTY or SELLER_GUILTY

        Map<String, Object> order = jdbc.queryForMap("""
            SELECT o.*, c.seller_id FROM orders o JOIN commodities c ON o.commodity_id = c.commodity_id WHERE o.order_id = ?
        """, orderId);

        BigDecimal productCost = (BigDecimal) order.get("product_cost");
        BigDecimal transportFee = (BigDecimal) order.get("transport_fee");
        BigDecimal totalRefund = productCost.add(transportFee);
        int sellerId = (int) order.get("seller_id");
        int buyerId = (int) order.get("buyer_id");
        Integer driverId = (Integer) order.get("driver_id");

        // Buyer always receives 100% refund for rejected bad goods
        jdbc.update("UPDATE users SET wallet_balance = wallet_balance + ? WHERE user_id = ?", totalRefund, buyerId);

        if ("DRIVER_GUILTY".equals(verdict)) {
            // Driver verified healthy cargo at farm, then damaged it:
            // 1. Seller STILL gets paid 100% of Product Cost from Driver's frozen collateral!
            jdbc.update("UPDATE users SET wallet_balance = wallet_balance + ?, trust_score = trust_score + 2 WHERE user_id = ?", productCost, sellerId);
            // 2. Driver forfeits transport fee, loses frozen collateral, and loses 25 Trust Points
            if (driverId != null) {
                jdbc.update("UPDATE users SET trust_score = trust_score - 25, is_suspended = (trust_score - 25 < 40) WHERE user_id = ?", driverId);
            }
            jdbc.update("UPDATE orders SET escrow_status = 'RESOLVED_DRIVER_GUILTY' WHERE order_id = ?", orderId);
        } else {
            // Seller Guilty: Seller gets 0 and loses 25 Trust Points; Driver gets transport fee & collateral back
            jdbc.update("UPDATE users SET trust_score = trust_score - 25, is_suspended = (trust_score - 25 < 40) WHERE user_id = ?", sellerId);
            if (driverId != null) {
                jdbc.update("UPDATE users SET wallet_balance = wallet_balance + ?, collateral_balance = collateral_balance + ?, trust_score = trust_score + 2 WHERE user_id = ?",
                        transportFee, productCost, driverId);
            }
            jdbc.update("UPDATE orders SET escrow_status = 'RESOLVED_SELLER_GUILTY' WHERE order_id = ?", orderId);
        }

        jdbc.update("UPDATE disputes SET ruling = ?, resolved_at = CURRENT_TIMESTAMP WHERE order_id = ?", verdict, orderId);
        return Map.of("status", "RESOLVED", "verdict", verdict);
    }

    // 13. Top Performers Leaderboard (Trust Score & Loyalty)
    @GetMapping("/leaderboard")
    public List> getLeaderboard() {
        return jdbc.queryForList("""
            SELECT user_id, full_name, role, trust_score, loyalty_points, is_suspended
            FROM users
            WHERE role IN ('FARMER', 'DRIVER', 'BUYER')
            ORDER BY trust_score DESC, loyalty_points DESC
            LIMIT 10
        """);
    }
}