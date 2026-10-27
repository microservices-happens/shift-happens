# AI-service: arbejdsnoter

## Formål og afgrænsning

Servicen foreslår en rangering af medarbejdere til en underbemandet vagt. Den
modtager `scheduling.shift.understaffed.v1` fra RabbitMQ og udsender
`ai.replacement.suggested.v1`. Den tildeler aldrig selv vagten. Scheduling skal
finde kvalificerede, ledige kandidater og kontrollere reglerne igen, når en leder
vælger en medarbejder.

Det gamle projekt i rodkatalogets `src/` bruges kun som reference. Denne kode
ligger under `services/ai-service/src/`.

## Opbygning

1. `infrastructure/messaging/AiMessaging.java` modtager eventen fra køen
   `ai.suggestions` og publicerer senere output fra outbox-tabellen.
2. `application/SuggestionService.java` læser kandidaterne, kalder Ollama og
   vælger modelrangering eller fallback. Modelkaldet sker uden en åben
   databasetransaktion.
3. `domain/Ranking.java` validerer, at alle og kun inputtets medarbejder-id'er
   bruges én gang, med fortløbende placeringer. Den kontrollerer også den ene
   samlede begrundelse på højst 100 ord.
4. `infrastructure/llm/OllamaClient.java` kalder Ollamas HTTP API med et
   JSON-skema, som begrænser id'erne i svaret.
5. `infrastructure/persistence/SuggestionPersistence.java` gemmer behandlet
   event-id, prompt-/svarlog og output-event i én kort transaktion.
   `SuggestionLogRetention.java` sletter prompt-/svarloggen efter syv dage.

Hvis Ollama er nede, svarer for sent eller giver et ugyldigt svar, rangeres
kandidaterne efter færrest planlagte timer i ugen. Output får `source: rule`;
et godkendt modelsvar får `source: llm`. Fallback- og modelresultater er kun
rådgivende.

## Lokal test

Fra roden `shift-happens` i PowerShell:

```powershell
docker compose -f docker-compose.microservices.yml --profile app --profile ai up -d --build ai-service ollama
docker compose -f docker-compose.microservices.yml --profile ai run --rm ollama-pull
.\scripts\ai-demo.ps1
```

Scriptet sender en syntetisk event med tre kandidater, udskriver den tilsvarende
forslagsevent og fjerner sin midlertidige outputkø. Det kan køres flere gange;
hver kørsel bruger et nyt event-id. Se årsagen til fallback med:

```powershell
docker compose -f docker-compose.microservices.yml logs --tail 100 ai-service
```

Unit- og servicetests køres med:

```powershell
.\mvnw.cmd -f services/ai-service/pom.xml test
```

## Erfaring fra første rigtige Ollama-test

- Med 20 sekunders timeout nåede modellen ikke at svare. Lokal Compose bruger
  nu 90 sekunder; demoscriptet venter op til 150 sekunder.
- Den første fuldførte modelrespons indeholdt et ukendt medarbejder-id og et
  forkert feltnavn. Valideringen afviste den korrekt. Med et JSON-skema i
  Ollama-kaldet fik vi efterfølgende et output med `source: llm`.
- Modellens begrundelse forvekslede kontrakttimer med allerede planlagte timer.
  Teknisk integration er derfor demonstreret, men forslagets faglige kvalitet
  er endnu ikke godkendt.
- PostgreSQL krævede `OffsetDateTime` ved opdatering af outbox-tidspunktet;
  dette blev rettet efter den første lokale kørsel.

## Næste skridt

1. Når Scheduling-servicen er klar: udsend rigtige underbemandings-events og
   vis forslagene via Scheduling's læsemodel. Hold AI-servicen adskilt fra
   rodkatalogets gamle `src/`.
2. Lav 10-20 realistiske testvagter og sammenlign modelrangering, fallback og
   begrundelser. Vurdér derefter, om `llama3.2:1b` er god nok.
3. Stram inputvalideringen for ugyldige eller dublerede medarbejder-id'er,
   negative timer og urimeligt store kandidatlister.
4. Overvej håndtering af samtidige genleveringer: databasen gemmer kun ét
   forslag, men to samtidige forbrugere kan begge nå at kalde modellen.

De fælles kontrakter ligger under `contracts/`; ændringer i dem bør aftales
med de services, der producerer eller forbruger eventene.
