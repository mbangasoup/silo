/* *********************************************************************** *
 * project: org.matsim.*												   *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2008 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */
package de.tum.bgu.msm.matsim;


import de.tum.bgu.msm.container.DataContainer;
import de.tum.bgu.msm.data.travelTimes.SkimTravelTimes;
import de.tum.bgu.msm.data.travelTimes.TravelTimes;
import de.tum.bgu.msm.matsim.accessibility.MatsimAccessibility;
import de.tum.bgu.msm.models.transportModel.TransportModel;
import de.tum.bgu.msm.properties.Properties;
import de.tum.bgu.msm.properties.modules.TransportModelPropertiesModule;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.dvrp.trafficmonitoring.TravelTimeUtils;
import org.matsim.contrib.noise.NoiseModule;
import org.matsim.contrib.noise.NoiseReceiverPoints;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.ControlerDefaults;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.Objects;

/**
 * @author dziemke, nkuehnel
 */
public final class MatsimTransportModel implements TransportModel {

    private static final Logger logger = Logger.getLogger(MatsimTransportModel.class);

    private final Properties properties;
    private final Config initialMatsimConfig;

    private final MatsimData matsimData;
    private final MatsimTravelTimes internalTravelTimes;

    private final DataContainer dataContainer;

    private MatsimScenarioAssembler scenarioAssembler;

    public MatsimTransportModel(DataContainer dataContainer, Config matsimConfig,
                                Properties properties, MatsimScenarioAssembler scenarioAssembler,
                                MatsimData matsimData) {
        this.dataContainer = Objects.requireNonNull(dataContainer);
        this.initialMatsimConfig = Objects.requireNonNull(matsimConfig,
                "No initial matsim config provided to SiloModel class!");

        final TravelTimes travelTimes = dataContainer.getTravelTimes();
        if (travelTimes instanceof MatsimTravelTimes) {
            this.internalTravelTimes = (MatsimTravelTimes) travelTimes;
        } else {
            this.internalTravelTimes = new MatsimTravelTimes(matsimConfig);
        }
        this.matsimData = matsimData;
        this.scenarioAssembler = scenarioAssembler;
        this.properties = properties;
    }

    @Override
    public void setup() {
        initialMatsimConfig.plansCalcRoute().setRoutingRandomness(0);
        internalTravelTimes.initialize(dataContainer.getGeoData(), matsimData);

        if (properties.transportModel.matsimInitialEventsFile == null) {
            runTransportModel(properties.main.startYear);
        } else {
            String eventsFile = properties.main.baseDirectory + properties.transportModel.matsimInitialEventsFile;
            replayFromEvents(eventsFile);
        }
    }

    @Override
    public void prepareYear(int year) {
    }

    @Override
    public void endYear(int year) {
        if (properties.transportModel.transportModelYears.contains(year + 1)) {
            runTransportModel(year + 1);
        }
    }

    @Override
    public void endSimulation() {
    }

    private void runTransportModel(int year) {
        logger.warn("Running MATSim transport model for year " + year + ".");
        Scenario assembledScenario;
        TravelTimes travelTimes = dataContainer.getTravelTimes();
        if (year == properties.main.baseYear &&
                properties.transportModel.transportModelIdentifier == TransportModelPropertiesModule.TransportModelIdentifier.MATSIM){
            //if using the SimpleCommuteModeChoiceScenarioAssembler, we need some intial travel times (this will use an unlodaded network)
            TravelTime myTravelTime = SiloMatsimUtils.getAnEmptyNetworkTravelTime();
            TravelDisutility myTravelDisutility = SiloMatsimUtils.getAnEmptyNetworkTravelDisutility();
            updateTravelTimes(myTravelTime, myTravelDisutility);
        }
        assembledScenario = scenarioAssembler.assembleScenario(initialMatsimConfig, year, travelTimes);

        ConfigUtils.setVspDefaults(assembledScenario.getConfig());
        finalizeConfig(assembledScenario.getConfig(), year);

        final Controler controler = new Controler(assembledScenario);

        controler.run();
        logger.warn("Running MATSim transport model for year " + year + " finished.");

        // Get travel Times from MATSim
        logger.warn("Using MATSim to compute travel times from zone to zone.");
        TravelTime travelTime = controler.getLinkTravelTimes();
        TravelDisutility travelDisutility = controler.getTravelDisutilityFactory().createTravelDisutility(travelTime);
        updateTravelTimes(travelTime, travelDisutility);
    }

    private void finalizeConfig(Config config, int year) {
        final String outputDirectoryRoot = properties.main.baseDirectory + "scenOutput/" + properties.main.scenarioName;
        String outputDirectory = outputDirectoryRoot + "/matsim/" + year + "/";
        config.controler().setRunId(String.valueOf(year));
        config.controler().setOutputDirectory(outputDirectory);
        config.controler().setWritePlansInterval(Math.max(config.controler().getLastIteration(), 1));
        config.controler().setWriteEventsInterval(Math.max(config.controler().getLastIteration(), 1));
        config.controler().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.transit().setUsingTransitInMobsim(false);

        config.vspExperimental().setWritingOutputEvents(true);
    }

    /**
     * @param eventsFile
     */
    private void replayFromEvents(String eventsFile) {
        logger.warn("Setting up MATSim with initial events file: " + eventsFile);
        Scenario scenario = ScenarioUtils.loadScenario(initialMatsimConfig);
        TravelTime travelTime = TravelTimeUtils.createTravelTimesFromEvents(scenario, eventsFile);
        TravelDisutility travelDisutility = ControlerDefaults.createDefaultTravelDisutilityFactory(scenario).createTravelDisutility(travelTime);
        updateTravelTimes(travelTime, travelDisutility);
    }

    private void updateTravelTimes(TravelTime travelTime, TravelDisutility disutility) {
        matsimData.update(disutility, travelTime);
        internalTravelTimes.update(matsimData);
        final TravelTimes mainTravelTimes = dataContainer.getTravelTimes();

        if (mainTravelTimes != this.internalTravelTimes && mainTravelTimes instanceof SkimTravelTimes) {
            ((SkimTravelTimes) mainTravelTimes).updateSkimMatrix(internalTravelTimes.getPeakSkim(TransportMode.car), TransportMode.car);
            if ((properties.transportModel.transportModelIdentifier == TransportModelPropertiesModule.TransportModelIdentifier.MATSIM)) {
                ((SkimTravelTimes) mainTravelTimes).updateSkimMatrix(internalTravelTimes.getPeakSkim(TransportMode.pt), TransportMode.pt);
            }
            ((SkimTravelTimes) mainTravelTimes).updateRegionalTravelTimes(dataContainer.getGeoData().getRegions().values(),
                    dataContainer.getGeoData().getZones().values());
        }
    }

    public MatsimData getMatsimData() {
        return matsimData;
    }
}
