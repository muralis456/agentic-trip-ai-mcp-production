package com.example.travel.service;

import com.example.travel.entity.UserPreference;
import com.example.travel.repository.UserPreferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class UserPreferenceService {

    private final UserPreferenceRepository repository;

    public UserPreferenceService(UserPreferenceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<UserPreference> find(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return repository.findById(userId);
    }

    @Transactional
    public void remember(String userId, String originIata, String travelStyle, String lastDestination) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        UserPreference preference = repository.findById(userId).orElseGet(UserPreference::new);
        preference.setUserId(userId);
        if (originIata != null && !originIata.isBlank()) {
            preference.setPreferredAirport(originIata);
        }
        if (travelStyle != null && !travelStyle.isBlank()) {
            preference.setTravelStyle(travelStyle);
        }
        if (lastDestination != null && !lastDestination.isBlank()) {
            preference.setLastDestination(lastDestination);
        }
        preference.setCurrency("INR");
        repository.save(preference);
    }
}
