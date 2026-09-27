package com.example.travel.config;

import com.example.travel.entity.AirportLocation;
import com.example.travel.repository.AirportLocationRepository;
import org.springframework.boot.CommandLineRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@Order(1)
public class AirportLocationSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AirportLocationSeeder.class);

    private final AirportLocationRepository repository;

    public AirportLocationSeeder(AirportLocationRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        List<AirportData> airports = List.of(
                new AirportData("Mumbai", "India", "Chhatrapati Shivaji Maharaj International Airport", "BOM", "VABB"),
                new AirportData("Delhi", "India", "Indira Gandhi International Airport", "DEL", "VIDP"),
                new AirportData("Bengaluru", "India", "Kempegowda International Airport", "BLR", "VOBL"),
                new AirportData("Goa", "India", "Dabolim Airport", "GOI", "VOGO"),
                new AirportData("Mopa", "India", "Manohar International Airport", "GOX", "VOGA"),
                new AirportData("Pune", "India", "Pune Airport", "PNQ", "VAPO"),
                new AirportData("Kochi", "India", "Cochin International Airport", "COK", "VOCI"),
                new AirportData("Jaipur", "India", "Jaipur International Airport", "JAI", "VIJP"),
                new AirportData("Ahmedabad", "India", "Sardar Vallabhbhai Patel International Airport", "AMD", "VAAH"),
                new AirportData("Chennai", "India", "Chennai International Airport", "MAA", "VOMM"),
                new AirportData("Hyderabad", "India", "Rajiv Gandhi International Airport", "HYD", "VOHS"),
                new AirportData("Kolkata", "India", "Netaji Subhas Chandra Bose International Airport", "CCU", "VECC"),
                new AirportData("Dubai", "United Arab Emirates", "Dubai International Airport", "DXB", "OMDB"),
                new AirportData("London", "United Kingdom", "Heathrow Airport", "LHR", "EGLL"),
                new AirportData("Paris", "France", "Charles de Gaulle Airport", "CDG", "LFPG"),
                new AirportData("New York", "United States", "John F. Kennedy International Airport", "JFK", "KJFK"),
                new AirportData("Los Angeles", "United States", "Los Angeles International Airport", "LAX", "KLAX"),
                new AirportData("Chicago", "United States", "O'Hare International Airport", "ORD", "KORD"),
                new AirportData("San Francisco", "United States", "San Francisco International Airport", "SFO", "KSFO"),
                new AirportData("Toronto", "Canada", "Toronto Pearson International Airport", "YYZ", "CYYZ"),
                new AirportData("Vancouver", "Canada", "Vancouver International Airport", "YVR", "CYVR"),
                new AirportData("Singapore", "Singapore", "Singapore Changi Airport", "SIN", "WSSS"),
                new AirportData("Bangkok", "Thailand", "Suvarnabhumi Airport", "BKK", "VTBS"),
                new AirportData("Tokyo", "Japan", "Narita International Airport", "NRT", "RJAA"),
                new AirportData("Osaka", "Japan", "Kansai International Airport", "KIX", "RJBB"),
                new AirportData("Seoul", "South Korea", "Incheon International Airport", "ICN", "RKSI"),
                new AirportData("Hong Kong", "Hong Kong", "Hong Kong International Airport", "HKG", "VHHH"),
                new AirportData("Beijing", "China", "Beijing Capital International Airport", "PEK", "ZBAA"),
                new AirportData("Shanghai", "China", "Shanghai Pudong International Airport", "PVG", "ZSPD"),
                new AirportData("Sydney", "Australia", "Sydney Kingsford Smith Airport", "SYD", "YSSY"),
                new AirportData("Melbourne", "Australia", "Melbourne Airport", "MEL", "YMML"),
                new AirportData("Auckland", "New Zealand", "Auckland Airport", "AKL", "NZAA"),
                new AirportData("Doha", "Qatar", "Hamad International Airport", "DOH", "OTHH"),
                new AirportData("Abu Dhabi", "United Arab Emirates", "Zayed International Airport", "AUH", "OMAA"),
                new AirportData("Istanbul", "Turkey", "Istanbul Airport", "IST", "LTFM"),
                new AirportData("Rome", "Italy", "Leonardo da Vinci International Airport", "FCO", "LIRF"),
                new AirportData("Amsterdam", "Netherlands", "Amsterdam Airport Schiphol", "AMS", "EHAM"),
                new AirportData("Frankfurt", "Germany", "Frankfurt Airport", "FRA", "EDDF"),
                new AirportData("Madrid", "Spain", "Adolfo Suarez Madrid-Barajas Airport", "MAD", "LEMD"),
                new AirportData("Barcelona", "Spain", "Barcelona-El Prat Airport", "BCN", "LEBL"),
                new AirportData("Zurich", "Switzerland", "Zurich Airport", "ZRH", "LSZH"),
                new AirportData("Vienna", "Austria", "Vienna International Airport", "VIE", "LOWW"),
                new AirportData("Cairo", "Egypt", "Cairo International Airport", "CAI", "HECA"),
                new AirportData("Cape Town", "South Africa", "Cape Town International Airport", "CPT", "FACT"),
                new AirportData("Nairobi", "Kenya", "Jomo Kenyatta International Airport", "NBO", "HKJK"),
                new AirportData("Sao Paulo", "Brazil", "Sao Paulo-Guarulhos International Airport", "GRU", "SBGR"),
                new AirportData("Mexico City", "Mexico", "Mexico City International Airport", "MEX", "MMMX"),
                new AirportData("Bali", "Indonesia", "Ngurah Rai International Airport", "DPS", "WADD")
        );

        int added = 0;
        for (AirportData data : airports) {
            if (repository.findByIataCodeIgnoreCase(data.iataCode()).isPresent()) {
                continue;
            }
            repository.save(toEntity(data));
            added++;
        }
        if (added > 0) {
            repository.flush();
            log.info("Airport directory added {} location(s); total={}", added, repository.count());
        } else {
            log.info("Airport directory already complete with {} locations", repository.count());
        }
    }

    private AirportLocation toEntity(AirportData data) {
        AirportLocation airport = new AirportLocation();
        airport.setCity(data.city());
        airport.setCountry(data.country());
        airport.setAirportName(data.airportName());
        airport.setIataCode(data.iataCode());
        airport.setIcaoCode(data.icaoCode());
        airport.setLocationType("international");
        return airport;
    }

    private record AirportData(String city, String country, String airportName, String iataCode, String icaoCode) {
    }
}
