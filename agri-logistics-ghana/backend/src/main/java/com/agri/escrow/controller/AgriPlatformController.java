package com.agri.escrow.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
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

    @GetMapping("/commodities")
    public List<Map<String, Object>> listCommodities() {
        return jdbc.queryForList("SELECT * FROM commodities");
    }
}
