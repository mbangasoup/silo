package de.tum.bgu.msm.scenarios.health;

import de.tum.bgu.msm.container.DataContainer;
import de.tum.bgu.msm.data.Day;
import de.tum.bgu.msm.models.AbstractModel;
import de.tum.bgu.msm.models.ModelUpdateListener;
import de.tum.bgu.msm.properties.Properties;
import org.apache.log4j.Logger;
import org.locationtech.jts.geom.Coordinate;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.analysis.spatial.Grid;
import org.matsim.contrib.analysis.time.TimeBinMap;
import org.matsim.contrib.emissions.Pollutant;
import org.matsim.contrib.emissions.analysis.EmissionGridAnalyzer;
import org.matsim.contrib.emissions.utils.EmissionsConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.*;

public class AirPollutantModel extends AbstractModel implements ModelUpdateListener {
    private int latestMatsimYear = -1;
    private static final Logger logger = Logger.getLogger(AirPollutantModel.class);
    private Scenario scenario;
    private final Config initialMatsimConfig;
    private final Set<Pollutant> pollutantSet = new HashSet<>();

    public AirPollutantModel(DataContainer dataContainer, Properties properties, Random random, Config config) {
        super(dataContainer, properties, random);
        this.initialMatsimConfig = config;
        Pollutant[] pollutants = new Pollutant[]{Pollutant.NO2,Pollutant.PM2_5,Pollutant.PM2_5_non_exhaust};
        for(Pollutant pollutant : pollutants){
            this.pollutantSet.add(pollutant);
        }
        ((HealthDataContainerImpl)dataContainer).setPollutantSet(pollutantSet);
    }

    @Override
    public void setup() {
        logger.warn("Air pollutant exposure model setup: ");
    }

    @Override
    public void prepareYear(int year) {
    }

    @Override
    public void endYear(int year) {
        logger.warn("Air pollutant exposure model end year:" + year);
        if(properties.main.startYear == year) {
            latestMatsimYear = year;
            for(Day day : Day.values()){
                createEmissionEventsOffline();
                assembleLinkExposure(year, day, runEmissionGridAnalyzer(year,day));
            }

        } else if(properties.transportModel.transportModelYears.contains(year + 1)) {//why year +1
            latestMatsimYear = year + 1;
            for(Day day : Day.values()){
                createEmissionEventsOffline();
                assembleLinkExposure(year, day, runEmissionGridAnalyzer(year,day));
            }
        }
    }


    @Override
    public void endSimulation() {
    }

    //TODO:
    private void createEmissionEventsOffline() {

    }


    private TimeBinMap<Grid<Map<Pollutant, Double>>> runEmissionGridAnalyzer(int year, Day day) {
        logger.info("Creating grid cell air pollutant exposure for year " + year + ".");

        initialMatsimConfig.addModule(new EmissionsConfigGroup());
        scenario = ScenarioUtils.createMutableScenario(initialMatsimConfig);
        scenario.getConfig().travelTimeCalculator().setTraveltimeBinSize(3600);

        String eventsFile;
        String networkFile;
        if (scenario.getConfig().controler().getRunId() == null || scenario.getConfig().controler().getRunId().equals("")) {
            eventsFile = scenario.getConfig().controler().getOutputDirectory() + "/" + day + "car/" + "output_events.xml.gz";
            networkFile = scenario.getConfig().controler().getOutputDirectory() + "/" + day + "car/" + "output_network.xml.gz";
        } else {
            eventsFile = scenario.getConfig().controler().getOutputDirectory() + "/" + day + "car/" + scenario.getConfig().controler().getRunId() + ".output_events.xml.gz";
            networkFile = scenario.getConfig().controler().getOutputDirectory() + "/" + day + "car/" + scenario.getConfig().controler().getRunId() + ".output_network.xml.gz";
        }

        new MatsimNetworkReader(scenario.getNetwork()).readFile(networkFile);

        EmissionGridAnalyzer gridAnalyzer =	new	EmissionGridAnalyzer.Builder()
                .withNetwork(scenario.getNetwork())
                .withTimeBinSize(3600*2)
                .withGridSize(20)
                .withSmoothingRadius(100)
                .withGridType(EmissionGridAnalyzer.GridType.Square)
                .build();

        return gridAnalyzer.process(eventsFile);
    }

    private void assembleLinkExposure(int year, Day day, TimeBinMap<Grid<Map<Pollutant, Double>>> gridTimeBinMap) {
        logger.info("Updating link air pollutant exposure for year " + year + ".");

        for	(TimeBinMap.TimeBin<Grid<Map<Pollutant,	Double>>> timebin :	gridTimeBinMap.getTimeBins()){
            int	startTime = (int) timebin.getStartTime();
            Grid<Map<Pollutant,	Double>> grid =	timebin.getValue();
            for(Link link : scenario.getNetwork().getLinks().values()){
                Map<Pollutant, Map<Integer, Double>> exposure2Pollutant2TimeBin =  new HashMap<>();

                Grid.Cell<Map<Pollutant,Double>> toNodeCell = grid.getCell(new Coordinate(link.getToNode().getCoord().getX(),link.getToNode().getCoord().getY()));
                Grid.Cell<Map<Pollutant,Double>> fromNodeCell = grid.getCell(new Coordinate(link.getFromNode().getCoord().getX(),link.getToNode().getCoord().getY()));

                for(Pollutant pollutant : pollutantSet){
                    //TODO: use avg as link exposure?
                    double avg = (toNodeCell.getValue().get(pollutant) + fromNodeCell.getValue().get(pollutant))/2;
                    if(exposure2Pollutant2TimeBin.get(pollutant)==null){
                        Map<Integer, Double> exposureByTimeBin = new HashMap<>();
                        exposureByTimeBin.put(startTime, avg);
                        exposure2Pollutant2TimeBin.put(pollutant, exposureByTimeBin);
                    }else {
                        exposure2Pollutant2TimeBin.get(pollutant).put(startTime, avg);
                    }
                }

                ((HealthDataContainerImpl)dataContainer).getLinkInfoByDay().get(day).get(link.getId()).setExposure2Pollutant2TimeBin(exposure2Pollutant2TimeBin);
            }
        }


    }
}
