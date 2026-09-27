package com.example.travel.service;

import com.example.travel.dto.TravelRequest;
import com.example.travel.entity.AirportLocation;
import com.example.travel.repository.AirportLocationRepository;
import com.example.travel.support.JsonSupport;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class QueryNormalizationServiceTest {

    @Test
    void usesLlmToResolveTypoWithoutHardcodedAlias() {
        AirportLocation hyderabad = airport("Hyderabad", "HYD", "India");
        AirportLocationRepository repository = mock(AirportLocationRepository.class);
        when(repository.findAll()).thenReturn(List.of(hyderabad));

        RoutedLlm llm = mock(RoutedLlm.class);
        JsonSupport json = mock(JsonSupport.class);
        when(llm.complete(any(), any(), any())).thenReturn(
                """
                {"normalizedPrompt":"any flights available from Hyderabad for today","corrections":[{"original":"hyderabd","corrected":"Hyderabad","type":"CITY","confidence":0.99}],"entities":[{"value":"Hyderabad","code":"HYD","country":"India","type":"CITY","confidence":0.99}]}
                """);
        doReturn(java.util.Optional.empty()).when(json).read(anyString(), any(Class.class));

        QueryNormalizationService service = new QueryNormalizationService(repository, llm, json);
        TravelRequest request = new TravelRequest();
        request.setPrompt("any flights available from hyderabd for today");

        QueryNormalizationService.NormalizationResult result = service.normalize(request);
        assertEquals(request.getPrompt(), result.normalizedPrompt());
        verify(llm).complete(any(), any(), any());
    }

    @Test
    void llmNormalizationDoesNotKeepADatabaseTransactionOpen() {
        RoutedLlm llm = mock(RoutedLlm.class);
        AtomicBoolean transactionActiveDuringLlmCall = new AtomicBoolean(true);
        when(llm.complete(any(), anyString(), anyString())).thenAnswer(invocation -> {
            transactionActiveDuringLlmCall.set(TransactionSynchronizationManager.isActualTransactionActive());
            return "{\"normalizedPrompt\":\"continue\",\"corrections\":[],\"entities\":[]}";
        });
        QueryNormalizationService service = new QueryNormalizationService(
                mock(AirportLocationRepository.class), llm,
                new JsonSupport(new tools.jackson.databind.ObjectMapper()));
        TravelRequest request = new TravelRequest();
        request.setPrompt("continue");

        service.normalize(request);

        assertFalse(transactionActiveDuringLlmCall.get(),
                "the LLM request must not hold a database connection in a read-only transaction");
    }

    private static AirportLocation airport(String city, String iata, String country) {
        AirportLocation a = new AirportLocation();
        a.setCity(city);
        a.setIataCode(iata);
        a.setCountry(country);
        return a;
    }
}
