# Project Airport & City Directory

This document mirrors the city/airport seed data in `AirportLocationSeeder`. It is intended for RAG entity resolution, destination recognition, airport/IATA context, and planning prompts. It is not a source of live flight schedules, fares, visa rules, or current airport status.

## How to use this knowledge

- Prefer exact IATA codes from this directory when resolving a city mentioned by the user.
- Treat city names and airport names as aliases for retrieval.
- Use MCP/live APIs for current flights, availability, weather, prices, and other time-sensitive information.
- When multiple airports exist for a destination (for example Goa/Mopa), let the flight-search capability determine current availability.

## Seeded locations

### India
- **Mumbai** — Chhatrapati Shivaji Maharaj International Airport; IATA `BOM`; ICAO `VABB`; aliases: Mumbai, BOM, Chhatrapati Shivaji Maharaj International Airport.
- **Delhi** — Indira Gandhi International Airport; IATA `DEL`; ICAO `VIDP`; aliases: Delhi, DEL, Indira Gandhi International Airport.
- **Bengaluru** — Kempegowda International Airport; IATA `BLR`; ICAO `VOBL`; aliases: Bengaluru, BLR, Kempegowda International Airport.
- **Goa** — Dabolim Airport; IATA `GOI`; ICAO `VOGO`; aliases: Goa, GOI, Dabolim Airport.
- **Mopa** — Manohar International Airport; IATA `GOX`; ICAO `VOGA`; aliases: Mopa, GOX, Manohar International Airport.
- **Pune** — Pune Airport; IATA `PNQ`; ICAO `VAPO`; aliases: Pune, PNQ, Pune Airport.
- **Kochi** — Cochin International Airport; IATA `COK`; ICAO `VOCI`; aliases: Kochi, COK, Cochin International Airport.
- **Jaipur** — Jaipur International Airport; IATA `JAI`; ICAO `VIJP`; aliases: Jaipur, JAI, Jaipur International Airport.
- **Ahmedabad** — Sardar Vallabhbhai Patel International Airport; IATA `AMD`; ICAO `VAAH`; aliases: Ahmedabad, AMD, Sardar Vallabhbhai Patel International Airport.
- **Chennai** — Chennai International Airport; IATA `MAA`; ICAO `VOMM`; aliases: Chennai, MAA, Chennai International Airport.
- **Hyderabad** — Rajiv Gandhi International Airport; IATA `HYD`; ICAO `VOHS`; aliases: Hyderabad, HYD, Rajiv Gandhi International Airport.
- **Kolkata** — Netaji Subhas Chandra Bose International Airport; IATA `CCU`; ICAO `VECC`; aliases: Kolkata, CCU, Netaji Subhas Chandra Bose International Airport.

### United Arab Emirates
- **Dubai** — Dubai International Airport; IATA `DXB`; ICAO `OMDB`; aliases: Dubai, DXB, Dubai International Airport.
- **Abu Dhabi** — Zayed International Airport; IATA `AUH`; ICAO `OMAA`; aliases: Abu Dhabi, AUH, Zayed International Airport.

### United Kingdom
- **London** — Heathrow Airport; IATA `LHR`; ICAO `EGLL`; aliases: London, LHR, Heathrow Airport.

### France
- **Paris** — Charles de Gaulle Airport; IATA `CDG`; ICAO `LFPG`; aliases: Paris, CDG, Charles de Gaulle Airport.

### United States
- **New York** — John F. Kennedy International Airport; IATA `JFK`; ICAO `KJFK`; aliases: New York, JFK, John F. Kennedy International Airport.
- **Los Angeles** — Los Angeles International Airport; IATA `LAX`; ICAO `KLAX`; aliases: Los Angeles, LAX, Los Angeles International Airport.
- **Chicago** — O'Hare International Airport; IATA `ORD`; ICAO `KORD`; aliases: Chicago, ORD, O'Hare International Airport.
- **San Francisco** — San Francisco International Airport; IATA `SFO`; ICAO `KSFO`; aliases: San Francisco, SFO, San Francisco International Airport.

### Canada
- **Toronto** — Toronto Pearson International Airport; IATA `YYZ`; ICAO `CYYZ`; aliases: Toronto, YYZ, Toronto Pearson International Airport.
- **Vancouver** — Vancouver International Airport; IATA `YVR`; ICAO `CYVR`; aliases: Vancouver, YVR, Vancouver International Airport.

### Singapore
- **Singapore** — Singapore Changi Airport; IATA `SIN`; ICAO `WSSS`; aliases: Singapore, SIN, Singapore Changi Airport.

### Thailand
- **Bangkok** — Suvarnabhumi Airport; IATA `BKK`; ICAO `VTBS`; aliases: Bangkok, BKK, Suvarnabhumi Airport.

### Japan
- **Tokyo** — Narita International Airport; IATA `NRT`; ICAO `RJAA`; aliases: Tokyo, NRT, Narita International Airport.
- **Osaka** — Kansai International Airport; IATA `KIX`; ICAO `RJBB`; aliases: Osaka, KIX, Kansai International Airport.

### South Korea
- **Seoul** — Incheon International Airport; IATA `ICN`; ICAO `RKSI`; aliases: Seoul, ICN, Incheon International Airport.

### Hong Kong
- **Hong Kong** — Hong Kong International Airport; IATA `HKG`; ICAO `VHHH`; aliases: Hong Kong, HKG, Hong Kong International Airport.

### China
- **Beijing** — Beijing Capital International Airport; IATA `PEK`; ICAO `ZBAA`; aliases: Beijing, PEK, Beijing Capital International Airport.
- **Shanghai** — Shanghai Pudong International Airport; IATA `PVG`; ICAO `ZSPD`; aliases: Shanghai, PVG, Shanghai Pudong International Airport.

### Australia
- **Sydney** — Sydney Kingsford Smith Airport; IATA `SYD`; ICAO `YSSY`; aliases: Sydney, SYD, Sydney Kingsford Smith Airport.
- **Melbourne** — Melbourne Airport; IATA `MEL`; ICAO `YMML`; aliases: Melbourne, MEL, Melbourne Airport.

### New Zealand
- **Auckland** — Auckland Airport; IATA `AKL`; ICAO `NZAA`; aliases: Auckland, AKL, Auckland Airport.

### Qatar
- **Doha** — Hamad International Airport; IATA `DOH`; ICAO `OTHH`; aliases: Doha, DOH, Hamad International Airport.

### Turkey
- **Istanbul** — Istanbul Airport; IATA `IST`; ICAO `LTFM`; aliases: Istanbul, IST, Istanbul Airport.

### Italy
- **Rome** — Leonardo da Vinci International Airport; IATA `FCO`; ICAO `LIRF`; aliases: Rome, FCO, Leonardo da Vinci International Airport.

### Netherlands
- **Amsterdam** — Amsterdam Airport Schiphol; IATA `AMS`; ICAO `EHAM`; aliases: Amsterdam, AMS, Amsterdam Airport Schiphol.

### Germany
- **Frankfurt** — Frankfurt Airport; IATA `FRA`; ICAO `EDDF`; aliases: Frankfurt, FRA, Frankfurt Airport.

### Spain
- **Madrid** — Adolfo Suarez Madrid-Barajas Airport; IATA `MAD`; ICAO `LEMD`; aliases: Madrid, MAD, Adolfo Suarez Madrid-Barajas Airport.
- **Barcelona** — Barcelona-El Prat Airport; IATA `BCN`; ICAO `LEBL`; aliases: Barcelona, BCN, Barcelona-El Prat Airport.

### Switzerland
- **Zurich** — Zurich Airport; IATA `ZRH`; ICAO `LSZH`; aliases: Zurich, ZRH, Zurich Airport.

### Austria
- **Vienna** — Vienna International Airport; IATA `VIE`; ICAO `LOWW`; aliases: Vienna, VIE, Vienna International Airport.

### Egypt
- **Cairo** — Cairo International Airport; IATA `CAI`; ICAO `HECA`; aliases: Cairo, CAI, Cairo International Airport.

### South Africa
- **Cape Town** — Cape Town International Airport; IATA `CPT`; ICAO `FACT`; aliases: Cape Town, CPT, Cape Town International Airport.

### Kenya
- **Nairobi** — Jomo Kenyatta International Airport; IATA `NBO`; ICAO `HKJK`; aliases: Nairobi, NBO, Jomo Kenyatta International Airport.

### Brazil
- **Sao Paulo** — Sao Paulo-Guarulhos International Airport; IATA `GRU`; ICAO `SBGR`; aliases: Sao Paulo, GRU, Sao Paulo-Guarulhos International Airport.

### Mexico
- **Mexico City** — Mexico City International Airport; IATA `MEX`; ICAO `MMMX`; aliases: Mexico City, MEX, Mexico City International Airport.

### Indonesia
- **Bali** — Ngurah Rai International Airport; IATA `DPS`; ICAO `WADD`; aliases: Bali, DPS, Ngurah Rai International Airport.

## Structured directory

| City | Country | Airport | IATA | ICAO |
|---|---|---|---|---|
| Mumbai | India | Chhatrapati Shivaji Maharaj International Airport | BOM | VABB |
| Delhi | India | Indira Gandhi International Airport | DEL | VIDP |
| Bengaluru | India | Kempegowda International Airport | BLR | VOBL |
| Goa | India | Dabolim Airport | GOI | VOGO |
| Mopa | India | Manohar International Airport | GOX | VOGA |
| Pune | India | Pune Airport | PNQ | VAPO |
| Kochi | India | Cochin International Airport | COK | VOCI |
| Jaipur | India | Jaipur International Airport | JAI | VIJP |
| Ahmedabad | India | Sardar Vallabhbhai Patel International Airport | AMD | VAAH |
| Chennai | India | Chennai International Airport | MAA | VOMM |
| Hyderabad | India | Rajiv Gandhi International Airport | HYD | VOHS |
| Kolkata | India | Netaji Subhas Chandra Bose International Airport | CCU | VECC |
| Dubai | United Arab Emirates | Dubai International Airport | DXB | OMDB |
| London | United Kingdom | Heathrow Airport | LHR | EGLL |
| Paris | France | Charles de Gaulle Airport | CDG | LFPG |
| New York | United States | John F. Kennedy International Airport | JFK | KJFK |
| Los Angeles | United States | Los Angeles International Airport | LAX | KLAX |
| Chicago | United States | O'Hare International Airport | ORD | KORD |
| San Francisco | United States | San Francisco International Airport | SFO | KSFO |
| Toronto | Canada | Toronto Pearson International Airport | YYZ | CYYZ |
| Vancouver | Canada | Vancouver International Airport | YVR | CYVR |
| Singapore | Singapore | Singapore Changi Airport | SIN | WSSS |
| Bangkok | Thailand | Suvarnabhumi Airport | BKK | VTBS |
| Tokyo | Japan | Narita International Airport | NRT | RJAA |
| Osaka | Japan | Kansai International Airport | KIX | RJBB |
| Seoul | South Korea | Incheon International Airport | ICN | RKSI |
| Hong Kong | Hong Kong | Hong Kong International Airport | HKG | VHHH |
| Beijing | China | Beijing Capital International Airport | PEK | ZBAA |
| Shanghai | China | Shanghai Pudong International Airport | PVG | ZSPD |
| Sydney | Australia | Sydney Kingsford Smith Airport | SYD | YSSY |
| Melbourne | Australia | Melbourne Airport | MEL | YMML |
| Auckland | New Zealand | Auckland Airport | AKL | NZAA |
| Doha | Qatar | Hamad International Airport | DOH | OTHH |
| Abu Dhabi | United Arab Emirates | Zayed International Airport | AUH | OMAA |
| Istanbul | Turkey | Istanbul Airport | IST | LTFM |
| Rome | Italy | Leonardo da Vinci International Airport | FCO | LIRF |
| Amsterdam | Netherlands | Amsterdam Airport Schiphol | AMS | EHAM |
| Frankfurt | Germany | Frankfurt Airport | FRA | EDDF |
| Madrid | Spain | Adolfo Suarez Madrid-Barajas Airport | MAD | LEMD |
| Barcelona | Spain | Barcelona-El Prat Airport | BCN | LEBL |
| Zurich | Switzerland | Zurich Airport | ZRH | LSZH |
| Vienna | Austria | Vienna International Airport | VIE | LOWW |
| Cairo | Egypt | Cairo International Airport | CAI | HECA |
| Cape Town | South Africa | Cape Town International Airport | CPT | FACT |
| Nairobi | Kenya | Jomo Kenyatta International Airport | NBO | HKJK |
| Sao Paulo | Brazil | Sao Paulo-Guarulhos International Airport | GRU | SBGR |
| Mexico City | Mexico | Mexico City International Airport | MEX | MMMX |
| Bali | Indonesia | Ngurah Rai International Airport | DPS | WADD |
