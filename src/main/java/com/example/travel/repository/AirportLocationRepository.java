package com.example.travel.repository;

import com.example.travel.entity.AirportLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AirportLocationRepository extends JpaRepository<AirportLocation, Long> {
    Optional<AirportLocation> findFirstByCityIgnoreCase(String city);
    Optional<AirportLocation> findFirstByCountryIgnoreCase(String country);
    Optional<AirportLocation> findByIataCodeIgnoreCase(String iataCode);
}
