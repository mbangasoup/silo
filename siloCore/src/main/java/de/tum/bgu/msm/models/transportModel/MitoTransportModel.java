package de.tum.bgu.msm.models.transportModel;

import com.vividsolutions.jts.geom.Coordinate;
import de.tum.bgu.msm.MitoModel;
import de.tum.bgu.msm.container.SiloDataContainer;
import de.tum.bgu.msm.data.*;
import de.tum.bgu.msm.data.dwelling.Dwelling;
import de.tum.bgu.msm.data.household.Household;
import de.tum.bgu.msm.data.household.HouseholdUtil;
import de.tum.bgu.msm.data.job.Job;
import de.tum.bgu.msm.data.jobTypes.kagawa.KagawaJobType;
import de.tum.bgu.msm.data.jobTypes.munich.MunichJobType;
import de.tum.bgu.msm.data.munich.MunichZone;
import de.tum.bgu.msm.data.person.Person;
import de.tum.bgu.msm.data.school.School;
import de.tum.bgu.msm.data.school.SchoolImpl;
import de.tum.bgu.msm.data.travelTimes.TravelTimes;
import de.tum.bgu.msm.models.AbstractModel;
import de.tum.bgu.msm.properties.Properties;
import de.tum.bgu.msm.utils.SiloUtil;
import org.apache.log4j.Logger;

import java.util.Map;
import java.util.Objects;

/**
 * Implementation of Transport Model Interface for MITO
 *
 * @author Rolf Moeckel
 * Created on February 18, 2017 in Munich, Germany
 */
public final class MitoTransportModel extends AbstractModel implements TransportModelI {

    private static final Logger logger = Logger.getLogger(MitoTransportModel.class);
    private TravelTimes travelTimes;
    private final String propertiesPath;
    private final String baseDirectory;

    public MitoTransportModel(String baseDirectory, SiloDataContainer dataContainer) {
        super(dataContainer);
        this.travelTimes = Objects.requireNonNull(dataContainer.getTravelTimes());
        this.propertiesPath = Objects.requireNonNull(Properties.get().main.baseDirectory + Properties.get().transportModel.mitoPropertiesPath);
        this.baseDirectory = Objects.requireNonNull(baseDirectory);
    }

    @Override
    public void runTransportModel(int year) {
        logger.info("  Running travel demand model MITO for the year " + year);
        DataSet dataSet = convertData(year);
        logger.info("  SILO data being sent to MITO");
        MitoModel mito = MitoModel.initializeModelFromSilo(propertiesPath, dataSet, Properties.get().main.scenarioName);
        mito.setRandomNumberGenerator(SiloUtil.getRandomObject());
        mito.setBaseDirectory(baseDirectory);
        mito.runModel();
    }

    private DataSet convertData(int year) {
        DataSet dataSet = new DataSet();
        convertZones(dataSet);
        fillMitoZoneEmployees(dataSet);
        convertSchools(dataSet);
        convertHhs(dataSet);
        dataSet.setTravelTimes(travelTimes);
        dataSet.setYear(year);
        return dataSet;
    }

    private void convertZones(DataSet dataSet) {
        for (Zone siloZone : dataContainer.getGeoData().getZones().values()) {
            MitoZone zone = new MitoZone(siloZone.getZoneId(), ((MunichZone) siloZone).getAreaType());
            zone.setShapeFeature(siloZone.getZoneFeature());
            dataSet.addZone(zone);
        }
    }

    private void convertSchools(DataSet dataSet) {
        Map<Integer, MitoZone> zones = dataSet.getZones();
        for (School school : dataContainer.getSchoolData().getSchools()) {
            MitoZone zone = zones.get(school.getZoneId());
            Coordinate coordinate;
            if (school instanceof SchoolImpl) {
                coordinate = ((SchoolImpl) school).getCoordinate();
            } else {
                coordinate = zone.getRandomCoord();
            }
            MitoSchool mitoSchool = new MitoSchool(zones.get(school.getZoneId()), coordinate, school.getId());
            zone.addSchoolEnrollment(school.getOccupancy());
            dataSet.addSchool(mitoSchool);
        }
    }

    private void convertHhs(DataSet dataSet) {
        Map<Integer, MitoZone> zones = dataSet.getZones();
        RealEstateDataManager realEstateData = dataContainer.getRealEstateData();
        int householdsSkipped = 0;
        int randomCoordCounter = 0;
        for (Household siloHousehold : dataContainer.getHouseholdData().getHouseholds()) {
            int zoneId = -1;
            Dwelling dwelling = realEstateData.getDwelling(siloHousehold.getDwellingId());
            if (dwelling != null) {
                zoneId = dwelling.getZoneId();

            }
            MitoZone zone = zones.get(zoneId);

            MitoHousehold household = new MitoHousehold(
                    siloHousehold.getId(),
                    HouseholdUtil.getHhIncome(siloHousehold) / 12,
                    siloHousehold.getAutos(), zone);

            Coordinate coordinate;
            if(dwelling instanceof MicroLocation && ((MicroLocation) dwelling).getCoordinate() != null) {
                coordinate = ((MicroLocation) dwelling).getCoordinate();
            } else {
                randomCoordCounter++;
                coordinate = zone.getRandomCoord();
            }

            //todo if there are housholds without adults they cannot be processed
            if (siloHousehold.getPersons().values().stream().anyMatch(p -> p.getAge() >= 18)) {
                    household.setHomeLocation(coordinate);
                    zone.addHousehold();
                    dataSet.addHousehold(household);
                    for (Person person : siloHousehold.getPersons().values()) {
                        MitoPerson mitoPerson = convertToMitoPp(person, dataSet);
                        household.addPerson(mitoPerson);
                        dataSet.addPerson(mitoPerson);
                    }
            } else {
                householdsSkipped++;
            }
        }
        logger.warn("There are " + randomCoordCounter + " households that were assigned a random coord inside their zone (" +
                randomCoordCounter / dataContainer.getHouseholdData().getHouseholds().size() * 100 + "%)");
        logger.warn("There are " + householdsSkipped + " households without adults that CANNOT be processed in MITO (" +
                householdsSkipped / dataContainer.getHouseholdData().getHouseholds().size() * 100 + "%)");
    }


    private MitoPerson convertToMitoPp(Person person, DataSet dataSet) {
        final MitoGender mitoGender = MitoGender.valueOf(person.getGender().name());
        final MitoOccupationStatus mitoOccupationStatus = MitoOccupationStatus.valueOf(person.getOccupation().getCode());

        MitoOccupation mitoOccupation = null;
        switch (mitoOccupationStatus) {
            case WORKER:
                if (person.getJobId() > 0) {
                    Job job = dataContainer.getJobData().getJobFromId(person.getJobId());
                    MitoZone zone = dataSet.getZones().get(job.getZoneId());
                    final Coordinate coordinate;
                    if (job instanceof MicroLocation) {
                        coordinate = ((MicroLocation) job).getCoordinate();
                    } else {
                        coordinate = zone.getRandomCoord();
                    }
                    mitoOccupation = new MitoJob(zone, coordinate, job.getId());
                }
                break;
            case UNEMPLOYED:
                break;
            case STUDENT:
                if (person.getSchoolId() > 0) {
                    mitoOccupation = dataSet.getSchools().get(person.getSchoolId());
                }
                break;
        }

        return new MitoPerson(
                person.getId(),
                mitoOccupationStatus,
                mitoOccupation,
                person.getAge(),
                mitoGender,
                person.hasDriverLicense());
    }

    private void fillMitoZoneEmployees(DataSet dataSet) {

        final Map<Integer, MitoZone> zones = dataSet.getZones();

        for (Job jj : dataContainer.getJobData().getJobs()) {
            final MitoZone zone = zones.get(jj.getZoneId());
            final String type = jj.getType().toUpperCase();
            try {
                de.tum.bgu.msm.data.jobTypes.JobType mitoJobType = null;
                switch (Properties.get().main.implementation) {
                    case MUNICH:
                        mitoJobType = MunichJobType.valueOf(type);
                        break;
                    case KAGAWA:
                        mitoJobType = KagawaJobType.valueOf(type);
                    default:
                        logger.error("Implementation " + Properties.get().main.implementation + " is not yet supported by MITO", new IllegalArgumentException());
                }
                zone.addEmployeeForType(mitoJobType);
            } catch (IllegalArgumentException e) {
                logger.warn("Job type " + type + " not defined for MITO implementation: " + Properties.get().main.implementation);
            }
        }
    }
}