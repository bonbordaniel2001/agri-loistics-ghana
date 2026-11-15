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

    @GetMapping("/health")
    public Map<String, String> health() {
        Map<String, String> res = new HashMap<>();
        res.put("status", "ONLINE");
        res.put("currency", "GHS");
        return res;
    }

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
        List> created = jdbc.queryForList("SELECT * FROM users WHERE phone = ?", phone);
        return created.get(0);
    }

    @GetMapping("/petrol-rate")
    public Map<String, Object> getPetrolRate() {
        return jdbc.queryForMap("SELECT setting_value AS petrolPrice FROM system_settings WHERE setting_key = 'PETROL_PRICE_GHS'");
    }

    @PostMapping("/petrol-rate")
    public Map<String, Object> updatePetrolRate(@RequestBody Map<String, Object> payload) {
        BigDecimal newRate = new BigDecimal(payload.get("petrolPrice").toString());
        jdbc.update("UPDATE system_settings SET setting_value = ? WHERE setting_key = 'PETROL_PRICE_GHS'", newRate);
        Map<String, Object> res = new HashMap<>();
        res.put("message", "Petrol price updated");
        res.put("petrolPrice", newRate);
        return res;
    }

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

        double qFactor = 1.00;
        if ("GRADE_A".equals(qualityGrade)) {
            qFactor = 1.15;
        } else if ("GRADE_C".equals(qualityGrade)) {
            qFactor = 0.80;
        }

        double ratio = supplyIndex > 0 ? (1.0 + (demandIndex - supplyIndex) / supplyIndex) : 1.0;
        BigDecimal finalUnitPrice = BigDecimal.valueOf(basePrice * qFactor * ratio).setScale(2, RoundingMode.HALF_UP);

        String sql = "INSERT INTO commodities (seller_id, title, category, local_unit, quantity, base_price, quality_grade, demand_index, supply_index, final_unit_price, image_url) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbc.update(sql, sellerId, title, category, localUnit, quantity, basePrice, qualityGrade, demandIndex, supplyIndex, finalUnitPrice, imageUrl);

        Map<String, Object> res = new HashMap<>();
        res.put("status", "LISTED");
        res.put("finalUnitPrice", finalUnitPrice);
        return res;
    }

    @GetMapping("/commodities")
    public List> listCommodities() {
        String sql = "SELECT c.*, u.full_name AS seller_name, u.trust_score AS seller_trust FROM commodities c JOIN users u ON c.seller_id = u.user_id WHERE c.is_available = TRUE AND c.quantity > 0 ORDER BY u.trust_score DESC, c.created_at DESC";
        return jdbc.queryForList(sql);
    }

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

        Map<String, Object> settingMap = jdbc.queryForMap("SELECT setting_value FROM system_settings WHERE setting_key = 'PETROL_PRICE_GHS'");
        BigDecimal petrolPrice = (BigDecimal) settingMap.get("setting_value");

        double baseRate = vehicleType.equals("KIA_TRUCK") ? 150.0 : 40.0;
        double burnRate = vehicleType.equals("KIA_TRUCK") ? 0.25 : 0.08;
        BigDecimal transportFee = BigDecimal.valueOf(baseRate + (distanceKm * burnRate * petrolPrice.doubleValue()))
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal totalEscrow = productCost.add(transportFee);

        jdbc.update("UPDATE users SET wallet_balance = wallet_balance - ? WHERE user_id = ?", totalEscrow, buyerId);
        jdbc.update("UPDATE commodities SET quantity = quantity - ? WHERE commodity_id = ?", qty, commodityId);

        String sql = "INSERT INTO orders (buyer_id, commodity_id, quantity_ordered, product_cost, transport_fee, vehicle_type, distance_km, escrow_status) VALUES (?, ?, ?, ?, ?, ?, ?, 'ESCROW_LOCKED')";
        jdbc.update(sql, buyerId, commodityId, qty, productCost, transportFee, vehicleType, distanceKm);

        Map<String, Object> res = new HashMap<>();
        res.put("status", "ESCROW_LOCKED");
        res.put("productCost", productCost);
        res.put("transportFee", transportFee);
        res.put("totalEscrow", totalEscrow);
        return res;
    }

    @GetMapping("/orders")
    public List> getAllOrders() {
        String sql = "SELECT o.*, c.title AS commodity_title, c.image_url AS listing_photo, c.seller_id, s.full_name AS seller_name, b.full_name AS buyer_name, d.full_name AS driver_name FROM orders o JOIN commodities c ON o.commodity_id = c.commodity_id JOIN users s ON c.seller_id = s.user_id JOIN users b ON o.buyer_id = b.user_id LEFT JOIN users d ON o.driver_id = d.user_id ORDER BY o.order_id DESC";
        return jdbc.queryForList(sql);
    }

    @PostMapping("/orders/{orderId}/driver-verify")
    @Transactional
    public Map<String, Object> driverVerifyPickup(@PathVariable int orderId, @RequestBody Map<String, Object> p) {
        int driverId = Integer.parseInt(p.get("driverId").toString());
        String pickupPhotoUrl = p.get("pickupPhotoUrl").toString();

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE order_id = ?", orderId);
        BigDecimal productCost = (BigDecimal) order.get("product_cost");

        jdbc.update("UPDATE users SET collateral_balance = collateral_balance - ? WHERE user_id = ?", productCost, driverId);
        
        String sql = "UPDATE orders SET driver_id = ?, pickup_photo_url = ?, escrow_status = 'IN_TRANSIT_VERIFIED' WHERE order_id = ?";
        jdbc.update(sql, driverId, pickupPhotoUrl, orderId);

        Map<String, Object> res = new HashMap<>();
        res.put("status", "IN_TRANSIT_VERIFIED");
        res.put("collateralFrozen", productCost);
        return res;
    }

    @PostMapping("/orders/{orderId}/gps")
    public Map<String, Object> updateGps(@PathVariable int orderId, @RequestBody Map<String, Object> p) {
        double lat = Double.parseDouble(p.get("lat").toString());
        double lng = Double.parseDouble(p.get("lng").toString());
        jdbc.update("UPDATE orders SET driver_lat = ?, driver_lng = ? WHERE order_id = ?", lat, lng, orderId);
        Map<String, Object> res = new HashMap<>();
        res.put("status", "GPS_UPDATED");
        res.put("lat", lat);
        res.put("lng", lng);
        return res;
    }

    @PostMapping("/orders/{orderId}/confirm-delivery")
    @Transactional
    public Map<String, Object> confirmDelivery(@PathVariable int orderId) {
        Map<String, Object> order = jdbc.queryForMap(
            "SELECT o.*, c.seller_id FROM orders o JOIN commodities c ON o.commodity_id = c.commodity_id WHERE o.order_id = ?", orderId
        );

        BigDecimal productCost = (BigDecimal) order.get("product_cost");
        BigDecimal transportFee = (BigDecimal) order.get("transport_fee");
        int sellerId = (int) order.get("seller_id");
        int buyerId = (int) order.get("buyer_id");
        Integer driverId = (Integer) order.get("driver_id");

        jdbc.update("UPDATE users SET wallet_balance = wallet_balance + ?, trust_score = trust_score + 5, loyalty_points = loyalty_points + 20 WHERE user_id = ?", productCost, sellerId);

        if (driverId != null) {
            jdbc.update("UPDATE users SET wallet_balance = wallet_balance + ?, collateral_balance = collateral_balance + ?, trust_score = trust_score + 5, loyalty_points = loyalty_points + 20 WHERE user_id = ?",
                    transportFee, productCost, driverId);
        }

        jdbc.update("UPDATE users SET loyalty_points = loyalty_points + 25 WHERE user_id = ?", buyerId);
        jdbc.update("UPDATE orders SET escrow_status = 'COMPLETED_SETTLED' WHERE order_id = ?", orderId);
        Map<String, Object> res = new HashMap<>();
        res.put("status", "COMPLETED_SETTLED");
        return res;
    }

    @PostMapping("/orders/{orderId}/dispute")
    @Transactional
    public Map<String, Object> raiseDispute(@PathVariable int orderId, @RequestBody Map<String, String> p) {
        String reason = p.get("reason");
        String rejectionPhotoUrl = p.getOrDefault("rejectionPhotoUrl", "");

        jdbc.update("UPDATE orders SET escrow_status = 'DISPUTED_FROZEN', rejection_photo_url = ? WHERE order_id = ?", rejectionPhotoUrl, orderId);
        jdbc.update("INSERT INTO disputes (order_id, reason) VALUES (?, ?)", orderId, reason);
        Map<String, Object> res = new HashMap<>();
        res.put("status", "DISPUTED_FROZEN");
        return res;
    }

    @PostMapping("/disputes/{orderId}/resolve")
    @Transactional
    public Map<String, Object> resolveDispute(@PathVariable int orderId, @RequestBody Map<String, String> p) {
        String verdict = p.get("verdict");

        Map<String, Object> order = jdbc.queryForMap(
            "SELECT o.*, c.seller_id FROM orders o JOIN commodities c ON o.commodity_id = c.commodity_id WHERE o.order_id = ?", orderId
        );

        BigDecimal productCost = (BigDecimal) order.get("product_cost");
        BigDecimal transportFee = (BigDecimal) order.get("transport_fee");
        BigDecimal totalRefund = productCost.add(transportFee);
        int sellerId = (int) order.get("seller_id");
        int buyerId = (int) order.get("buyer_id");
        Integer driverId = (Integer) order.get("driver_id");

        jdbc.update("UPDATE users SET wallet_balance = wallet_balance + ? WHERE user_id = ?", totalRefund, buyerId);

        if ("DRIVER_GUILTY".equals(verdict)) {
            jdbc.update("UPDATE users SET wallet_balance = wallet_balance + ?, trust_score = trust_score + 2 WHERE user_id = ?", productCost, sellerId);
            if (driverId != null) {
                jdbc.update("UPDATE users SET trust_score = trust_score - 25 WHERE user_id = ?", driverId);
            }
            jdbc.update("UPDATE orders SET escrow_status = 'RESOLVED_DRIVER_GUILTY' WHERE order_id = ?", orderId);
        } else {
            jdbc.update("UPDATE users SET trust_score = trust_score - 25 WHERE user_id = ?", sellerId);
            if (driverId != null) {
                jdbc.update("UPDATE users SET wallet_balance = wallet_balance + ?, collateral_balance = collateral_balance + ?, trust_score = trust_score + 2 WHERE user_id = ?",
                        transportFee, productCost, driverId);
            }
            jdbc.update("UPDATE orders SET escrow_status = 'RESOLVED_SELLER_GUILTY' WHERE order_id = ?", orderId);
        }

        jdbc.update("UPDATE disputes SET ruling = ?, resolved_at = CURRENT_TIMESTAMP WHERE order_id = ?", verdict, orderId);
        Map<String, Object> res = new HashMap<>();
        res.put("status", "RESOLVED");
        res.put("verdict", verdict);
        return res;
    }

    @GetMapping("/leaderboard")
    public List> getLeaderboard() {
        String sql = "SELECT user_id, full_name, role, trust_score, loyalty_points, is_suspended FROM users WHERE role IN ('FARMER', 'DRIVER', 'BUYER') ORDER BY trust_score DESC, loyalty_points DESC LIMIT 10";
        return jdbc.queryForList(sql);
    }
}