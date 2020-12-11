package de.tum.bgu.msm.syntheticPopulationGenerator.bangkok.allocation;


import de.tum.bgu.msm.container.DataContainer;
import de.tum.bgu.msm.data.person.Person;
import de.tum.bgu.msm.syntheticPopulationGenerator.DataSetSynPop;
import de.tum.bgu.msm.syntheticPopulationGenerator.ModuleSynPop;
import de.tum.bgu.msm.syntheticPopulationGenerator.bangkok.allocation.*;
import de.tum.bgu.msm.syntheticPopulationGenerator.properties.PropertiesSynPop;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;

public class Allocation extends ModuleSynPop{

    private static final Logger logger = Logger.getLogger(Allocation.class);
    private final DataContainer dataContainer;
    private HashMap<Person, Integer> educationalLevel;

    public Allocation(DataSetSynPop dataSetSynPop, DataContainer dataContainer){
        super(dataSetSynPop);
        this.dataContainer = dataContainer;
    }

    @Override
    public void run(){
        logger.info("   Started allocation model.");
        if (PropertiesSynPop.get().main.runAllocation) {
            generateHouseholdsPersonsDwellings();
            generateVacantDwellings();
            generateJobs();
            generateAutos();
        } else {
            readPopulation();
        }
        if (PropertiesSynPop.get().main.runJobAllocation) {
            assignJobs();
            assignSchools();
            validateTripLengths();
        }
        logger.info("   Completed allocation model.");

    }

    public void generateHouseholdsPersonsDwellings(){
        educationalLevel = new HashMap<>();
        new GenerateHouseholdsPersonsDwellings(dataContainer, dataSetSynPop, educationalLevel).run();
    }

    public void generateJobs(){
        new GenerateJobs(dataContainer, dataSetSynPop).run();
    }

    public void assignJobs(){
        new AssignJobs(dataContainer, dataSetSynPop, educationalLevel).run();
    }

    public void assignSchools(){
        new AssignSchools(dataContainer, dataSetSynPop).run();
    }

    public void generateAutos() {new CarOwnership().run();}

    public void readPopulation(){
        educationalLevel = new HashMap<>();
        new ReadPopulation(dataContainer, educationalLevel).run();
    }

    public void validateTripLengths(){
        new ValidateTripLengthDistribution(dataContainer, dataSetSynPop).run();
    }


    public void addBoroughsAsCities(int county){
        //Add to the municipality list the boroughs, because they have weights as well
        //Only if the option of running IPU with three areas is true
        ArrayList<Integer> newCities = new ArrayList<>();
        for (int city : dataSetSynPop.getMunicipalities()){
            if (!dataSetSynPop.getMunicipalitiesByCounty().get(county).contains(city)){
                newCities.add(city);
            }
        }
        newCities.addAll(dataSetSynPop.getBoroughsByCounty().get(county));
        dataSetSynPop.setMunicipalities(newCities);
    }

    public void removeBoroughsAsCities(int county){
        //Remove to the list of boroughs at the municipality, because they have weights for household generation but are not on the job allocation explicitly
        //Only if the option of running IPU with three areas is true
        ArrayList<Integer> newCities = dataSetSynPop.getMunicipalitiesByCounty().get(county);
        newCities.removeAll(dataSetSynPop.getBoroughsByCounty().get(county));
        dataSetSynPop.setMunicipalities(newCities);
    }

    private void generateVacantDwellings(){
        new GenerateVacantDwellings(dataContainer, dataSetSynPop).run();
    }
}
