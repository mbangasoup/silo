package de.tum.bgu.msm.models.relocation;

import de.tum.bgu.msm.container.DataContainer;
import de.tum.bgu.msm.data.Region;
import de.tum.bgu.msm.data.Zone;
import de.tum.bgu.msm.data.dwelling.Dwelling;
import de.tum.bgu.msm.data.dwelling.RealEstateDataManager;
import de.tum.bgu.msm.data.household.*;
import de.tum.bgu.msm.data.job.Job;
import de.tum.bgu.msm.data.job.JobDataManager;
import de.tum.bgu.msm.data.person.Occupation;
import de.tum.bgu.msm.data.person.Person;
import de.tum.bgu.msm.data.person.PersonCapeTown;
import de.tum.bgu.msm.data.person.RaceCapeTown;
import de.tum.bgu.msm.models.relocation.moves.AbstractMovesModelImpl;
import de.tum.bgu.msm.models.relocation.moves.DwellingProbabilityStrategy;
import de.tum.bgu.msm.models.relocation.moves.MovesStrategy;
import de.tum.bgu.msm.properties.Properties;
import de.tum.bgu.msm.util.matrices.IndexedDoubleMatrix1D;
import de.tum.bgu.msm.utils.SiloUtil;
import org.matsim.api.core.v01.TransportMode;

import java.util.*;

public class MovesModelCapeTown extends AbstractMovesModelImpl {

    private final DwellingUtilityStrategyCapeTown ddUtilityStrategy;
    private final SelectRegionStrategyCapeTown regionStrategy;
    private final DwellingProbabilityStrategy ddProbabilityStrategy;

    private final Map<Integer, Map<RaceCapeTown, Double>> personShareByRaceByRegion = new HashMap<>();
    private final Map<Integer, Map<RaceCapeTown, Double>> personShareByRaceByZone = new HashMap<>();

    private IndexedDoubleMatrix1D ppByRegion;
    private IndexedDoubleMatrix1D ppByZone;

    private final Map<IncomeCategory, Map<RaceCapeTown, Map<Integer, Double>>> utilityByRegionByRaceByIncome = new EnumMap<>(IncomeCategory.class);

    public MovesModelCapeTown(DataContainer dataContainer, Properties properties,
                              MovesStrategy movesStrategy, DwellingUtilityStrategyCapeTown ddUtilityStrategy,
                              SelectRegionStrategyCapeTown regionStrategy, DwellingProbabilityStrategy ddProbabilityStrategy) {
        super(dataContainer, properties, movesStrategy);
        this.ddUtilityStrategy = ddUtilityStrategy;
        this.regionStrategy = regionStrategy;
        this.ddProbabilityStrategy = ddProbabilityStrategy;
    }

    @Override
    protected double calculateHousingUtility(Household hh, Dwelling dd) {
        double ddQualityUtility = convertQualityToUtility(dd.getQuality());
        double ddSizeUtility = convertAreaToUtility(dd.getBedrooms());
        double ddAutoAccessibilityUtility = convertAccessToUtility(accessibility.getAutoAccessibilityForZone(dd.getZoneId()));
        double transitAccessibilityUtility = convertAccessToUtility(accessibility.getTransitAccessibilityForZone(dd.getZoneId()));
        HouseholdType ht = hh.getHouseholdType();
        double ddPriceUtility = convertPriceToUtility(dd.getPrice(), ht);

        //currently this is re-filtering persons to find workers (it was done previously in select region)
        // This way looks more flexible to account for other trips, such as education, though.

        double travelCostUtility = 1; //do not have effect at the moment;

        Map<Person, Job> jobsForThisHousehold = new HashMap<>();
        JobDataManager jobDataManager = dataContainer.getJobDataManager();
        for (Person pp : hh.getPersons().values()) {
            if (pp.getOccupation() == Occupation.EMPLOYED && pp.getJobId() != -2) {
                Job workLocation = Objects.requireNonNull(jobDataManager.getJobFromId(pp.getJobId()));
                jobsForThisHousehold.put(pp, workLocation);
            }
        }
        double workDistanceUtility = 1;
        for (Job workLocation : jobsForThisHousehold.values()) {
            double factorForThisZone = accessibility.getCommutingTimeProbability(Math.max(1, (int) dataContainer.getTravelTimes().getTravelTime(
                    dd, workLocation, properties.transportModel.peakHour_s, TransportMode.car)));
            workDistanceUtility *= factorForThisZone;
        }
        return ddUtilityStrategy.calculateSelectDwellingUtility(ht, ddSizeUtility, ddPriceUtility,
                ddQualityUtility, ddAutoAccessibilityUtility,
                transitAccessibilityUtility, workDistanceUtility);
    }

    @Override
    protected void calculateRegionalUtilities() {
        logger.info("Calculating regional utilities");
        utilityByRegionByRaceByIncome.clear();
        calculateRacialSharesByZoneAndRegion();
        final Map<Integer, Double> rentsByRegion = calculateRegionalPrices();
        for (IncomeCategory incomeCategory : IncomeCategory.values()) {
            EnumMap<RaceCapeTown, Map<Integer, Double>> utilityByRegionByRace = new EnumMap<>(RaceCapeTown.class);
            for (RaceCapeTown race : RaceCapeTown.values()) {
                Map<Integer, Double> utilityByRegion = new HashMap<>();
                for (Region region : geoData.getRegions().values()) {
                    final int averageRegionalRent = rentsByRegion.get(region.getId()).intValue();
                    final float regAcc = (float) convertAccessToUtility(accessibility.getRegionalAccessibility(region.getId()));
                    float priceUtil = (float) convertPriceToUtility(averageRegionalRent, incomeCategory);
                    utilityByRegion.put(region.getId(),
                            regionStrategy.calculateSelectRegionProbability(incomeCategory,
                                    race, priceUtil, regAcc, personShareByRaceByRegion.get(region.getId()).get(race)));

                }
                utilityByRegionByRace.put(race, utilityByRegion);
            }
            utilityByRegionByRaceByIncome.put(incomeCategory, utilityByRegionByRace);
        }
    }

    private void calculateRacialSharesByZoneAndRegion() {
        ppByRegion.assign(0);
        ppByZone.assign(0);

        for (Region region : geoData.getRegions().values()) {
            personShareByRaceByRegion.put(region.getId(), new EnumMap<>(RaceCapeTown.class));
            for (Zone zone : region.getZones()) {
                personShareByRaceByZone.put(zone.getZoneId(), new EnumMap<>(RaceCapeTown.class));
            }
        }

        for (Household hh : dataContainer.getHouseholdDataManager().getHouseholds()) {
            int zone = -1;
            Dwelling dwelling = dataContainer.getRealEstateDataManager().getDwelling(hh.getDwellingId());
            if (dwelling != null) {
                zone = dwelling.getZoneId();
            }
            final int region = geoData.getZones().get(zone).getRegion().getId();
            for (Person person : hh.getPersons().values()) {
                RaceCapeTown race = ((PersonCapeTown) person).getRace();

                personShareByRaceByRegion.get(region).merge(race, 1., (oldValue, newValue) -> oldValue + newValue);
                personShareByRaceByZone.get(region).merge(race, 1., (oldValue, newValue) -> oldValue + newValue);

                ppByZone.setIndexed(zone, ppByZone.getIndexed(zone) + 1);
                ppByRegion.setIndexed(region, ppByRegion.getIndexed(region) + 1);
            }
        }

        for (Region region : geoData.getRegions().values()) {
            personShareByRaceByRegion.get(region.getId()).replaceAll((raceCapeTown, count) -> {
                if (count == null || ppByRegion.getIndexed(region.getId()) == 0.) {
                    return 0.;
                } else {
                    return count / ppByRegion.getIndexed(region.getId());
                }
            });
            for (Zone zone : region.getZones()) {
                personShareByRaceByZone.get(zone.getZoneId()).replaceAll((raceCapeTown, count) -> {
                    if (count == null || ppByZone.getIndexed(zone.getZoneId()) == 0.) {
                        return 0.;
                    } else {
                        return count / ppByZone.getIndexed(zone.getZoneId());
                    }
                });
            }
        }
    }

    @Override
    protected boolean isHouseholdEligibleToLiveHere(Household household, Dwelling dd) {
        return true;
    }

    @Override
    public int searchForNewDwelling(Household household) {
        // search alternative dwellings

        // data preparation
        int householdIncome = 0;
        Map<Person, Zone> workerZonesForThisHousehold = new HashMap<>();
        JobDataManager jobDataManager = dataContainer.getJobDataManager();
        RealEstateDataManager realEstateDataManager = dataContainer.getRealEstateDataManager();
        for (Person pp: household.getPersons().values()) {
            if (pp.getOccupation() == Occupation.EMPLOYED && pp.getJobId() != -2) {
                Zone workZone = geoData.getZones().get(jobDataManager.getJobFromId(pp.getJobId()).getZoneId());
                workerZonesForThisHousehold.put(pp, workZone);
                householdIncome += pp.getIncome();
            }
        }

        HouseholdType ht = HouseholdUtil.defineHouseholdType(household);
        RaceCapeTown race = ((HouseholdCapeTown) household).getRace();

        // Step 1: select region
        Map<Integer, Double> regionUtilitiesForThisHousehold  = new HashMap<>();
        regionUtilitiesForThisHousehold.putAll(getUtilitiesByRegionForThisHousehold(ht, race,workerZonesForThisHousehold.values()));

        // todo: adjust probabilities to make that households tend to move shorter distances (dist to work is already represented)
        String normalizer = "powerOfPopulation";
        int totalVacantDd = 0;
        for (int region: geoData.getRegions().keySet()) {
            totalVacantDd += realEstateDataManager.getNumberOfVacantDDinRegion(region);
        }
        for (int region : regionUtilitiesForThisHousehold.keySet()){
            switch (normalizer) {
                case ("vacDd"): {
                    // Multiply utility of every region by number of vacant dwellings to steer households towards available dwellings
                    // use number of vacant dwellings to calculate attractivity of region
                    regionUtilitiesForThisHousehold.put(region, regionUtilitiesForThisHousehold.get(region) * (float) realEstateDataManager.getNumberOfVacantDDinRegion(region));
                } case ("shareVacDd"): {
                    // use share of empty dwellings to calculate attractivity of region
                    regionUtilitiesForThisHousehold.put(region, regionUtilitiesForThisHousehold.get(region) * ((float) realEstateDataManager.getNumberOfVacantDDinRegion(region) / (float) totalVacantDd));
                } case ("dampenedVacRate"): {
                    double x = (double) realEstateDataManager.getNumberOfVacantDDinRegion(region) /
                            (double) realEstateDataManager.getNumberOfVacantDDinRegion(region) * 100d;  // % vacancy
                    double y = 1.4186E-03 * Math.pow(x, 3) - 6.7846E-02 * Math.pow(x, 2) + 1.0292 * x + 4.5485E-03;
                    y = Math.min(5d, y);                                                // % vacancy assumed to be ready to move in
                    regionUtilitiesForThisHousehold.put(region, regionUtilitiesForThisHousehold.get(region) * (y / 100d * realEstateDataManager.getNumberOfVacantDDinRegion(region)));
                    if (realEstateDataManager.getNumberOfVacantDDinRegion(region) < 1) {
                        regionUtilitiesForThisHousehold.put(region, 0D);
                    }
                } case ("population"): {
                    regionUtilitiesForThisHousehold.put(region, regionUtilitiesForThisHousehold.get(region) * ppByRegion.getIndexed(region));
                } case ("noNormalization"): {
                    // do nothing
                }case ("powerOfPopulation"): {
                    regionUtilitiesForThisHousehold.put(region, regionUtilitiesForThisHousehold.get(region) * Math.pow(ppByRegion.getIndexed(region),0.5));
                }
            }
        }

        int selectedRegionId;
        if (regionUtilitiesForThisHousehold.values().stream().mapToDouble(i -> i).sum() == 0) {
            return -1;
        } else {
            selectedRegionId = SiloUtil.select(regionUtilitiesForThisHousehold);
        }


        // Step 2: select vacant dwelling in selected region
        int[] vacantDwellings = realEstateDataManager.getListOfVacantDwellingsInRegion(selectedRegionId);
        double[] expProbs = SiloUtil.createArrayWithValue(vacantDwellings.length, 0d);
        double sumProbs = 0.;
        int maxNumberOfDwellings = Math.min(20, vacantDwellings.length);  // No household will evaluate more than 20 dwellings
        float factor = ((float) maxNumberOfDwellings / (float) vacantDwellings.length);
        for (int i = 0; i < vacantDwellings.length; i++) {
            if (SiloUtil.getRandomNumberAsFloat() > factor) continue;
            Dwelling dd = realEstateDataManager.getDwelling(vacantDwellings[i]);
            double util = calculateHousingUtility(household, dd);
            expProbs[i] = ddProbabilityStrategy.calculateSelectDwellingProbability(util);
            sumProbs =+ expProbs[i];
        }
        if (sumProbs == 0) return -1;    // could not find dwelling that fits restrictions
        int selected = SiloUtil.select(expProbs, sumProbs);
        return vacantDwellings[selected];

    }

    private Map<Integer, Double> getUtilitiesByRegionForThisHousehold(HouseholdType ht, RaceCapeTown race, Collection<Zone> workZones){
        Map<Integer, Double> utilitiesForThisHousehold
                = new HashMap<>(utilityByRegionByRaceByIncome.get(ht.getIncomeCategory()).get(race));

        for(Region region : geoData.getRegions().values()){
            double thisRegionFactor = 1;
            if (workZones != null) {
                for (Zone workZone : workZones) {
                    int timeFromZoneToRegion = (int) dataContainer.getTravelTimes().getTravelTimeToRegion(
                            workZone, region, properties.transportModel.peakHour_s, TransportMode.car);
                    thisRegionFactor = thisRegionFactor * accessibility.getCommutingTimeProbability(timeFromZoneToRegion);
                }
            }
            utilitiesForThisHousehold.put(region.getId(),utilitiesForThisHousehold.get(region.getId())*thisRegionFactor);
        }
        return utilitiesForThisHousehold;
    }

    @Override
    public void endSimulation() {

    }
}
