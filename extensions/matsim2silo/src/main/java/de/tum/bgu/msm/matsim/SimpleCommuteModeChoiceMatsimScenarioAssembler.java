package de.tum.bgu.msm.matsim;

import de.tum.bgu.msm.container.DataContainer;
import de.tum.bgu.msm.data.dwelling.Dwelling;
import de.tum.bgu.msm.data.dwelling.RealEstateDataManager;
import de.tum.bgu.msm.data.household.Household;
import de.tum.bgu.msm.data.household.HouseholdDataManager;
import de.tum.bgu.msm.data.job.Job;
import de.tum.bgu.msm.data.job.JobDataManager;
import de.tum.bgu.msm.data.person.Occupation;
import de.tum.bgu.msm.data.person.Person;
import de.tum.bgu.msm.data.travelTimes.TravelTimes;
import de.tum.bgu.msm.models.modeChoice.CommuteModeChoice;
import de.tum.bgu.msm.models.modeChoice.CommuteModeChoiceMapping;
import de.tum.bgu.msm.properties.Properties;
import de.tum.bgu.msm.utils.SiloUtil;
import org.apache.log4j.Logger;
import org.locationtech.jts.geom.Coordinate;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.Config;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.scenario.ScenarioUtils;

public class SimpleCommuteModeChoiceMatsimScenarioAssembler implements MatsimScenarioAssembler {

    private final static Logger logger = Logger.getLogger(SimpleMatsimScenarioAssembler.class);

    private final DataContainer dataContainer;
    private CommuteModeChoice commuteModeChoice;
    private final Properties properties;

    public SimpleCommuteModeChoiceMatsimScenarioAssembler(DataContainer dataContainer, Properties properties, CommuteModeChoice commuteModeChoice) {
        this.dataContainer = dataContainer;
        this.commuteModeChoice = commuteModeChoice;
        this.properties = properties;
    }

    @Override
    public Scenario assembleScenario(Config matsimConfig, int year, TravelTimes travelTimes) {
        logger.info("Starting creating MATSim scenario.");
        double populationScalingFactor = properties.transportModel.matsimScaleFactor;
        SiloMatsimUtils.checkSiloPropertiesAndMatsimConfigConsistency(matsimConfig, properties);

//        if(dataContainer.getTravelTimes() instanceof SkimTravelTimes) {
//
//        }
//        if(year == properties.main.startYear) {
//            logger.info("Referring to the simple MATSim scenario assembler for the base year as no travel times are available yet.");
//            return new SimpleMatsimScenarioAssembler(dataContainer, properties).assembleScenario(initialMatsimConfig, year);
//        } else {
//            travelTimes = dataContainer.getTravelTimes();
//        }

        Scenario scenario = ScenarioUtils.loadScenario(matsimConfig);
        Population matsimPopulation = scenario.getPopulation();


        HouseholdDataManager householdDataManager = dataContainer.getHouseholdDataManager();
        JobDataManager jobDataManager = dataContainer.getJobDataManager();
        RealEstateDataManager realEstateDataManager = dataContainer.getRealEstateDataManager();

        for (Household household: householdDataManager.getHouseholds()) {
            if (SiloUtil.getRandomNumberAsDouble() > populationScalingFactor) {
                continue;
            }
            Dwelling dwelling = realEstateDataManager.getDwelling(household.getDwellingId());
            CommuteModeChoiceMapping commuteModeChoiceMapping = commuteModeChoice.assignCommuteModeChoice(
                    dwelling, travelTimes, household);

            for (Person person: household.getPersons().values()) {
                if (person.getOccupation() != Occupation.EMPLOYED || person.getJobId() == -2) { // i.e. person does not work
                    continue;
                }
                String mode = commuteModeChoiceMapping.getMode(person).mode;
                int noHHAUtos = household.getAutos();
                if (mode.equals(TransportMode.car)) {
                    Coord dwellingCoord = getOrRandomlyChooseDwellingCoord(dwelling);

                    Job job = jobDataManager.getJobFromId(person.getJobId());
                    Coord jobCoord = getOrRandomlyChooseJobCoordinate(job);

                    createHWHPlan(matsimPopulation, person, dwellingCoord, job, jobCoord, noHHAUtos, TransportMode.car);
                } else {
                    if (!properties.transportModel.onlySimulateCarTrips) {
                        Coord dwellingCoord = getOrRandomlyChooseDwellingCoord(dwelling);

                        Job job = jobDataManager.getJobFromId(person.getJobId());
                        Coord jobCoord = getOrRandomlyChooseJobCoordinate(job);

                        createHWHPlan(matsimPopulation, person, dwellingCoord, job, jobCoord, noHHAUtos, mode);
                    }
                }
            }
        }
        logger.info("Finished creating MATSim scenario.");
        return scenario;
    }

    private Coord getOrRandomlyChooseDwellingCoord(Dwelling dwelling) {
        Coordinate dwellingCoordinate;
        if (dwelling != null && dwelling.getCoordinate() != null) {
            dwellingCoordinate = dwelling.getCoordinate();
        } else {
            dwellingCoordinate = dataContainer.getGeoData().getZones().get(dwelling.getZoneId()).getRandomCoordinate(SiloUtil.getRandomObject());
        }
        return new Coord(dwellingCoordinate.x, dwellingCoordinate.y);
    }

    private Coord getOrRandomlyChooseJobCoordinate(Job job) {
        Coordinate jobCoordinate;
        if (job != null && job.getCoordinate() != null) {
            jobCoordinate = job.getCoordinate();
        } else {
            jobCoordinate = dataContainer.getGeoData().getZones().get(job.getZoneId()).getRandomCoordinate(SiloUtil.getRandomObject());
        }
        return new Coord(jobCoordinate.x, jobCoordinate.y);
    }

    private void createHWHPlan(Population matsimPopulation, Person person, Coord dwellingCoord, Job job, Coord jobCoord, int noHHAUtos, String transportMode) {
        PopulationFactory populationFactory = matsimPopulation.getFactory();

        org.matsim.api.core.v01.population.Person matsimPerson = populationFactory.createPerson(Id.createPersonId(person.getId()));

        if (noHHAUtos > 0 && person.hasDriverLicense()) {
            PersonUtils.setCarAvail(matsimPerson, "maybe");
        } else {
            PersonUtils.setCarAvail(matsimPerson, "never"); // Needs to be exaclty this string to work, cf. PermissibleModesCalculator:69
        }

        matsimPopulation.addPerson(matsimPerson);

        Plan matsimPlan = populationFactory.createPlan();
        matsimPerson.addPlan(matsimPlan);

        Activity activity1 = populationFactory.createActivityFromCoord("home", dwellingCoord);
        Integer departureTime = defineDepartureFromHome(job);
        activity1.setEndTime(departureTime);
        matsimPlan.addActivity(activity1);
        matsimPlan.addLeg(populationFactory.createLeg(transportMode));

        Activity activity2 = populationFactory.createActivityFromCoord("work", jobCoord);
        activity2.setEndTime(defineWorkEndTime(job, departureTime));
        matsimPlan.addActivity(activity2);
        matsimPlan.addLeg(populationFactory.createLeg(transportMode));

        Activity activity3 = populationFactory.createActivityFromCoord("home", dwellingCoord);

        matsimPlan.addActivity(activity3);
    }

    /**
     * Defines departure time from home. Note that it actually tries to use job start times if defined. Otherwise
     * randomly draws from a normal distribution around the peak hour with 1 hour standard deviation.
     */
    private Integer defineDepartureFromHome(Job job) {
        return job.getStartTimeInSeconds().orElse(Math.max(0, (int) (properties.transportModel.peakHour_s + SiloUtil.getRandomObject().nextGaussian() * 3600)));
    }

    /**
     * Defines departure time from work. Note that it actually tries to use job duration times if defined. Otherwise
     * randomly draws from a normal distribution with mean of 8 hours with 1 hour standard deviation. The duration
     * is then added to the job starting time.
     */
    private int defineWorkEndTime(Job job, int departureTime) {
        return departureTime + job.getWorkingTimeInSeconds().orElse(Math.max(0, (int) (8*3600 + SiloUtil.getRandomObject().nextGaussian() * 3600)));
    }
}