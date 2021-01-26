package de.tum.bgu.msm.data.household;

import de.tum.bgu.msm.data.Id;
import de.tum.bgu.msm.data.person.Person;

import java.util.Map;
import java.util.Optional;

public interface Household extends Id {

    int getHhSize();

    int getDwellingId();

    int getAutos();

    Map<Integer, ? extends Person> getPersons();

    HouseholdType getHouseholdType();

    void updateHouseholdType();

    void setDwelling(int id);

    void addPerson(Person person);

    void removePerson(int personId);

    void setAutos(int autos);

    Optional<Object> getAttribute(String key);

    void setAttribute(String key, Object value);
}
