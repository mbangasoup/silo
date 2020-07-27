package de.tum.bgu.msm.matsim;

import de.tum.bgu.msm.data.Location;
import de.tum.bgu.msm.data.MicroLocation;
import de.tum.bgu.msm.data.Region;
import de.tum.bgu.msm.data.Zone;
import de.tum.bgu.msm.data.geo.GeoData;
import de.tum.bgu.msm.data.travelTimes.TravelTimes;
import de.tum.bgu.msm.properties.Properties;
import de.tum.bgu.msm.util.matrices.IndexedDoubleMatrix2D;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.Config;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.FreespeedFactorRoutingModule;
import org.matsim.core.router.TripRouter;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.facilities.ActivityFacilitiesFactory;
import org.matsim.facilities.ActivityFacilitiesFactoryImpl;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.Facility;

import java.util.*;

/**
 * @author dziemke, nkuehnel
 */
public final class MatsimTravelTimes implements TravelTimes {

    private final static Logger logger = Logger.getLogger(MatsimTravelTimes.class);

    private MatsimData matsimData;

    private final Map<String, IndexedDoubleMatrix2D> skimsByMode = new HashMap<>();
    private Map<Integer, Zone> zones;

    private TripRouter tripRouter;

    private final Map<String, IndexedDoubleMatrix2D> travelTimesFromRegion = new LinkedHashMap<>();
    private final Map<String, IndexedDoubleMatrix2D> travelTimesToRegion = new LinkedHashMap<>();
    private Collection<Region> regions;

    private final Config config;

    public MatsimTravelTimes(Config config) {
        this.config = config;
    }

    public void initialize(GeoData geoData, MatsimData matsimData) {
        this.zones = geoData.getZones();
        this.matsimData = matsimData;
        regions = geoData.getRegions().values();
    }

    public void update(MatsimData matsimData) {
        this.matsimData = matsimData;
        this.tripRouter = matsimData.createTripRouter();
        this.skimsByMode.clear();
        this.travelTimesFromRegion.clear();
        this.travelTimesToRegion.clear();
        updateSkims();
        updateRegionalTravelTimes();
    }

    private void updateSkims() {
        logger.info("Updating car and pt skim.");
        getPeakSkim(TransportMode.car);
        getPeakSkim(TransportMode.pt);
    }

    private void updateRegionalTravelTimes() {
        logger.info("Updating minimal zone to region travel times...");
        IndexedDoubleMatrix2D travelTimesFromRegionCar = new IndexedDoubleMatrix2D(regions, zones.values());
        IndexedDoubleMatrix2D travelTimesToRegionCar = new IndexedDoubleMatrix2D(zones.values(), regions);
        IndexedDoubleMatrix2D travelTimesFromRegionPt = new IndexedDoubleMatrix2D(regions, zones.values());
        IndexedDoubleMatrix2D travelTimesToRegionPt = new IndexedDoubleMatrix2D(zones.values(), regions);

        regions.parallelStream().forEach( r -> {
            for(Zone zone: zones.values()) {
                int zoneId = zone.getZoneId();
                double minFromCar = Double.MAX_VALUE;
                double minToCar = Double.MAX_VALUE;
                double minFromPt = Double.MAX_VALUE;
                double minToPt = Double.MAX_VALUE;

                for (Zone zoneInRegion : r.getZones()) {
                    double travelTimeFromRegionCar = getPeakSkim(TransportMode.car).getIndexed(zoneInRegion.getZoneId(), zoneId);
                    if (travelTimeFromRegionCar < minFromCar) {
                        minFromCar = travelTimeFromRegionCar;
                    }
                    double travelTimeToRegionCar = getPeakSkim(TransportMode.car).getIndexed(zoneId, zoneInRegion.getZoneId());
                    if (travelTimeToRegionCar < minToCar) {
                        minToCar = travelTimeToRegionCar;
                    }
                    double travelTimeFromRegionPt = getPeakSkim(TransportMode.pt).getIndexed(zoneInRegion.getZoneId(), zoneId);
                    if (travelTimeFromRegionCar < minFromPt) {
                        minFromPt = travelTimeFromRegionPt;
                    }
                    double travelTimeToRegionPt = getPeakSkim(TransportMode.pt).getIndexed(zoneId, zoneInRegion.getZoneId());
                    if (travelTimeToRegionPt < minToPt) {
                        minToPt = travelTimeToRegionPt;
                    }
                }
                travelTimesFromRegionCar.setIndexed(r.getId(), zoneId, minFromCar);
                travelTimesToRegionCar.setIndexed(zoneId, r.getId(), minToCar);
                travelTimesFromRegionPt.setIndexed(r.getId(), zoneId, minFromPt);
                travelTimesToRegionPt.setIndexed(zoneId, r.getId(), minToPt);
            }
        });
        travelTimesFromRegion.put(TransportMode.car, travelTimesFromRegionCar);
        travelTimesFromRegion.put(TransportMode.pt, travelTimesFromRegionPt);
        travelTimesToRegion.put(TransportMode.car, travelTimesToRegionCar);
        travelTimesToRegion.put(TransportMode.pt, travelTimesToRegionPt);
    }

    // TODO Use travel costs?
    @Override
    public double getTravelTime(Location origin, Location destination, double timeOfDay_s, String mode) {


        Coord originCoord;
        Coord destinationCoord;
        if (origin instanceof MicroLocation && destination instanceof MicroLocation) {
            // Microlocations case
            originCoord = CoordUtils.createCoord(((MicroLocation) origin).getCoordinate());
            destinationCoord = CoordUtils.createCoord(((MicroLocation) destination).getCoordinate());
        } else if (origin instanceof Zone && destination instanceof Zone) {
            // Non-microlocations case
            originCoord = matsimData.getZoneConnectorManager().getCoordsForZone((Zone) origin).get(0);
            destinationCoord = matsimData.getZoneConnectorManager().getCoordsForZone((Zone) destination).get(0);
        } else {
            throw new IllegalArgumentException("Origin and destination have to be consistent in location type!");
        }

        Id<Link> fromLink = null;
        Id<Link> toLink = null;
        if(tripRouter.getRoutingModule(mode) instanceof FreespeedFactorRoutingModule) {
            final Network carNetwork = matsimData.getCarNetwork();
            fromLink = NetworkUtils.getNearestLink(carNetwork, originCoord).getId();
            toLink = NetworkUtils.getNearestLink(carNetwork, destinationCoord).getId();
        }

        ActivityFacilitiesFactoryImpl activityFacilitiesFactory = new ActivityFacilitiesFactoryImpl();
        Facility fromFacility = ((ActivityFacilitiesFactory) activityFacilitiesFactory).createActivityFacility(Id.create(1, ActivityFacility.class), originCoord, fromLink);
        Facility toFacility = ((ActivityFacilitiesFactory) activityFacilitiesFactory).createActivityFacility(Id.create(2, ActivityFacility.class), destinationCoord, toLink);
        List<? extends PlanElement> planElements = tripRouter.calcRoute(mode, fromFacility, toFacility, timeOfDay_s, null);
        double arrivalTime = timeOfDay_s;

        if (!planElements.isEmpty()) {
            final Leg lastLeg = (Leg) planElements.get(planElements.size() - 1);
            arrivalTime = lastLeg.getDepartureTime().seconds() + lastLeg.getTravelTime().seconds();
        }

        double time = arrivalTime - timeOfDay_s;

        //convert to minutes
        time /= 60.;
        return time;
    }

    @Override
    public double getTravelTimeFromRegion(Region origin, Zone destination, double timeOfDay_s, String mode) {
        return travelTimesFromRegion.get(mode).getIndexed(origin.getId(), destination.getZoneId());
    }

    @Override
    public double getTravelTimeToRegion(Zone origin, Region destination, double timeOfDay_s, String mode) {
        return travelTimesToRegion.get(mode).getIndexed(origin.getZoneId(), destination.getId());
    }

    @Override
    public IndexedDoubleMatrix2D getPeakSkim(String mode) {
        if (skimsByMode.containsKey(mode)) {
            return skimsByMode.get(mode);
        } else {
            logger.info("Calculating skim matrix for mode " + mode +
                    " using " + Properties.get().main.numberOfThreads + " threads.");
            IndexedDoubleMatrix2D skim;
            final MatsimSkimCreator matsimSkimCreator = new MatsimSkimCreator(matsimData);
            switch (mode) {
                case TransportMode.car:
                    skim = matsimSkimCreator.createCarSkim(zones.values());
                    break;
                case TransportMode.pt:
                    if (config.transit().isUseTransit()) {
                        skim = matsimSkimCreator.createPtSkim(zones.values());
                        break;
                    } else {
                        logger.warn("No schedule/ network provided for pt. Will use freespeed factor.");
                        skim = matsimSkimCreator.createFreeSpeedFactorSkim(zones.values(),
                                config.plansCalcRoute().getModeRoutingParams().get(TransportMode.pt).getTeleportedModeFreespeedFactor());
                        break;
                    }
                default:
                    logger.warn("Defaulting to teleportation.");
                    skim = matsimSkimCreator.createTeleportedSkim(zones.values(), mode);
            }
            skimsByMode.put(mode, skim);
            logger.info("Obtained skim for mode " + mode);
            return skim;
        }
    }

    @Override
    public TravelTimes duplicate() {
        logger.warn("Creating another TravelTimes object.");
        MatsimTravelTimes matsimTravelTimes = new MatsimTravelTimes(config);
        matsimTravelTimes.zones = this.zones;
        matsimTravelTimes.regions = this.regions;
        matsimTravelTimes.matsimData = matsimData;
        matsimTravelTimes.tripRouter = matsimData.createTripRouter();
        matsimTravelTimes.skimsByMode.putAll(this.skimsByMode);
        matsimTravelTimes.travelTimesFromRegion.putAll(travelTimesFromRegion);
        matsimTravelTimes.travelTimesToRegion.putAll(travelTimesToRegion);
        return matsimTravelTimes;
    }
}