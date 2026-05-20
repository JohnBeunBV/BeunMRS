# OpenMRS Communicatiemodule

## Context
De platformen voor berichtenverkeer verschillen sterk per regio. In China worden bijvoorbeeld andere systemen gebruikt (zoals Baidu) dan in Europa (zoals WhatsApp).  
Door het ontbreken van standaardisatie is voor elk platform een aparte koppeling nodig. WhatsApp en Signal hebben bijvoorbeeld verschillende API’s.

De opdrachtgever wil de communicatiemodule als zelfstandig product aanbieden aan OpenMRS-organisaties wereldwijd. Door het als SaaS te positioneren kunnen organisaties zich abonneren op de dienst zonder zelf infrastructuur te beheren, wat de adoptie vereenvoudigt.

## Doelstelling
Ontwerp en implementeer een Software-as-a-Service communicatiemodule die notificaties kan versturen voor OpenMRS organisaties via externe messaging providers.

Gebruik hiervoor de fictieve messaging providers:
- SwiftSend  
- LegacyLink  
- AsyncFlow  
- SecurePost  

De module dient te voldoen aan de HL7-standaarden volgens de FHIR-specificatie.

De communicatiemodule dient configureerbaar en uitbreidbaar te zijn, zodat OpenMRS-organisaties hun eigen abonnementen en services kunnen integreren. Daarnaast dient het ontwerp toekomstbestendig te zijn, zodat nieuwe communicatieproviders eenvoudig kunnen worden toegevoegd.

## Functionele eisen

### 1. Notificaties voor patiënten
Als een patiënt van een ziekenhuis wil ik een bericht op mijn telefoon ontvangen met de details van mijn afspraak (tijd, locatie en eventuele voorbereidingen), zodat ik mijn ziekenhuisbezoek goed kan voorbereiden en op tijd kan verschijnen.

**De notificatie wordt:**
- 24 uur voor de afspraak verzonden  
- 1 uur voor de afspraak verzonden  

**De notificatie bevat:**
- Datum en tijd van de afspraak  
- Locatie (bijvoorbeeld polikliniek en kamer)  
- Eventuele specifieke instructies (bijvoorbeeld nuchter blijven of medicijnen meenemen)  

**Regels:**
- Voor afspraken die reeds zijn aangevangen worden geen notificaties verstuurd  
- Wanneer een afspraak binnen OpenMRS wordt geannuleerd of gewijzigd:
  - worden er geen notificaties meer verzonden, of  
  - worden de verzendtijden aangepast  

---

### 2. Logging en controle
De communicatiemodule legt vast of de notificatie succesvol is verzonden, zodat later overzichten kunnen worden gegenereerd:
- Welke notificaties zijn verstuurd  
- Namens welke organisatie  
- Via welke messaging provider  

Dit maakt het controleren van facturen van messaging providers eenvoudiger.

---

### 3. Gebruik van messaging providers
Als OpenMRS-organisatie wil ik dat de communicatiemodule gebruik maakt van één van de ondersteunde messaging providers om berichten naar mijn patiënten te versturen.

---

## Niet-functionele eisen

### 1. SaaS en multi-tenant gebruik
De communicatiemodule dient zelfstandig te kunnen functioneren en integreren met meerdere OpenMRS-instanties. Hierdoor kunnen verschillende ziekenhuizen de module gebruiken op basis van eigen abonnementen met messaging providers.

### 2. Integratie en beveiliging
De integratie tussen de OpenMRS-instantie en de communicatiemodule:
- is passend bij de doelstelling  
- is gedocumenteerd voor technische OpenMRS-beheerders  
- is beveiligd volgens best practices die passen bij de gekozen integratie-oplossing  

### 3. Ondersteunde messaging providers
Organisaties die gebruik willen maken van de communicatiemodule moeten gebruik kunnen maken van één van de volgende providers:

- SwiftSend  
- LegacyLink  
- AsyncFlow  