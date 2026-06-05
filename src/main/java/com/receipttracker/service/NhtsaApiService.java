package com.receipttracker.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Proxies the NHTSA public APIs — no authentication required.
 * Results are cached in memory (makes for 24h, models per make+year for 12h).
 * On any API error, returns empty list (non-fatal — user can type manually).
 */
@Service
public class NhtsaApiService {

    private static final Logger log = LoggerFactory.getLogger(NhtsaApiService.class);

    private static final String VPIC_BASE  = "https://vpic.nhtsa.dot.gov/api";
    private static final String RECALL_BASE = "https://api.nhtsa.gov";

    private final RestTemplate restTemplate = new RestTemplate();

    // ── Simple time-bounded in-memory cache ───────────────────────────────────

    private record CacheEntry<T>(T data, long expiresAt) {}

    private final Map<String, CacheEntry<List<String>>> cache = new ConcurrentHashMap<>();

    private List<String> fromCache(String key) {
        CacheEntry<List<String>> entry = cache.get(key);
        if (entry != null && System.currentTimeMillis() < entry.expiresAt()) return entry.data();
        return null;
    }

    private void toCache(String key, List<String> data, long ttlMillis) {
        cache.put(key, new CacheEntry<>(data, System.currentTimeMillis() + ttlMillis));
    }

    // ── VPIC ─────────────────────────────────────────────────────────────────

    /** Returns sorted list of car/light-truck make names (cached 24h). */
    public List<String> getMakes() {
        String key = "makes";
        List<String> cached = fromCache(key);
        if (cached != null) return cached;

        try {
            String url = VPIC_BASE + "/vehicles/GetMakesForVehicleType/car?format=json";
            VpicMakesResponse resp = restTemplate.getForObject(url, VpicMakesResponse.class);
            if (resp == null || resp.results == null) return List.of();

            List<String> makes = resp.results.stream()
                    .map(m -> m.makeName)
                    .filter(Objects::nonNull)
                    .sorted()
                    .collect(Collectors.toList());

            toCache(key, makes, 24L * 60 * 60 * 1000);
            log.debug("NHTSA: loaded {} makes", makes.size());
            return makes;
        } catch (Exception e) {
            log.warn("NHTSA getMakes failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Returns sorted model names for a given make + year (cached 12h). */
    public List<String> getModels(String make, int year) {
        String key = "models_" + make.toLowerCase() + "_" + year;
        List<String> cached = fromCache(key);
        if (cached != null) return cached;

        try {
            String url = VPIC_BASE + "/vehicles/GetModelsForMakeYear/make/"
                    + make + "/modelyear/" + year + "?format=json";
            VpicModelsResponse resp = restTemplate.getForObject(url, VpicModelsResponse.class);
            if (resp == null || resp.results == null) return List.of();

            List<String> models = resp.results.stream()
                    .map(m -> m.modelName)
                    .filter(Objects::nonNull)
                    .sorted()
                    .collect(Collectors.toList());

            toCache(key, models, 12L * 60 * 60 * 1000);
            return models;
        } catch (Exception e) {
            log.warn("NHTSA getModels failed make={} year={}: {}", make, year, e.getMessage());
            return List.of();
        }
    }

    /** Decodes a VIN and returns a map of decoded values. */
    public Map<String, String> decodeVin(String vin, Integer year) {
        try {
            String yearParam = year != null ? "?modelyear=" + year : "";
            String url = VPIC_BASE + "/vehicles/DecodeVinValues/" + vin + yearParam + "&format=json";
            VpicDecodeResponse resp = restTemplate.getForObject(url, VpicDecodeResponse.class);
            if (resp == null || resp.results == null || resp.results.isEmpty()) return Map.of();

            Map<String, Object> raw = resp.results.get(0);
            Map<String, String> result = new LinkedHashMap<>();
            safeGet(raw, "Make",       result, "make");
            safeGet(raw, "Model",      result, "model");
            safeGet(raw, "ModelYear",  result, "year");
            safeGet(raw, "Trim",       result, "trim");
            safeGet(raw, "BodyClass",  result, "bodyClass");
            safeGet(raw, "FuelTypePrimary", result, "fuelType");
            return result;
        } catch (Exception e) {
            log.warn("NHTSA decodeVin failed vin={}: {}", vin, e.getMessage());
            return Map.of();
        }
    }

    private void safeGet(Map<String, Object> src, String srcKey, Map<String, String> dest, String destKey) {
        Object val = src.get(srcKey);
        if (val instanceof String s && !s.isBlank()) dest.put(destKey, s);
    }

    // ── Recalls ───────────────────────────────────────────────────────────────

    public List<Map<String, String>> getRecalls(String make, String model, int year) {
        try {
            String url = RECALL_BASE + "/recalls/recallsByVehicle?make="
                    + make + "&model=" + model + "&modelYear=" + year;
            RecallResponse resp = restTemplate.getForObject(url, RecallResponse.class);
            if (resp == null || resp.results == null) return List.of();

            return resp.results.stream().map(r -> {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("campaignNumber", r.nhtsaCampaignNumber);
                m.put("component",      r.component);
                m.put("summary",        r.summary);
                m.put("consequence",    r.consequence);
                m.put("remedy",         r.remedy);
                m.put("manufacturer",   r.manufacturer);
                return m;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("NHTSA getRecalls failed make={} model={} year={}: {}", make, model, year, e.getMessage());
            return List.of();
        }
    }

    // ── VPIC response POJOs ───────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class VpicMakesResponse {
        @JsonProperty("Results") List<MakeResult> results;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class MakeResult {
        @JsonProperty("MakeName") String makeName;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class VpicModelsResponse {
        @JsonProperty("Results") List<ModelResult> results;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ModelResult {
        @JsonProperty("Model_Name") String modelName;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class VpicDecodeResponse {
        @JsonProperty("Results") List<Map<String, Object>> results;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RecallResponse {
        @JsonProperty("results") List<RecallResult> results;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RecallResult {
        @JsonProperty("NHTSACampaignNumber") String nhtsaCampaignNumber;
        @JsonProperty("Component")           String component;
        @JsonProperty("Summary")             String summary;
        @JsonProperty("Consequence")         String consequence;
        @JsonProperty("Remedy")              String remedy;
        @JsonProperty("Manufacturer")        String manufacturer;
    }
}
