package com.example.travel.jev;

import com.example.travel.graph.TravelState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
@ConditionalOnBean(JevDecisionService.class)
public class JevProviderDecisionService {
    private final JevDecisionService decisions;
    public JevProviderDecisionService(JevDecisionService decisions){this.decisions=decisions;}
    public Decision choose(TravelState state,String failure){
        try{
            var d=decisions.choose(Map.of("capability","flight search","failure",failure==null?"":failure,"request",state.userRequest()),
                "Choose the preferred flight provider to try next. This is only a routing preference; authorization, availability, circuit state and actual fallback remain deterministic server policy.",
                Map.of("DUFFEL","Use Duffel when available for structured live flight offers.","IGNAV","Use Ignav as the alternative flight provider.","AVIATIONSTACK","Use AviationStack as the legacy/fallback provider."));
            String c=d.choice().toUpperCase();
            return d.accepted()&&Map.of("DUFFEL",1,"IGNAV",1,"AVIATIONSTACK",1).containsKey(c)?new Decision(c,d.confidence(),true,"jev"):new Decision("IGNAV",d.confidence(),false,"fallback");
        }catch(Exception ex){return new Decision("IGNAV",0,false,"jev unavailable: "+ex.getClass().getSimpleName());}
    }
    public record Decision(String provider,double confidence,boolean accepted,String reason){}
}
