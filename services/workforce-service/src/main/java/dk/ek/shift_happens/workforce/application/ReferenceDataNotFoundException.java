package dk.ek.shift_happens.workforce.application;

public class ReferenceDataNotFoundException extends RuntimeException {

    public ReferenceDataNotFoundException(String resource, long id) {
        super(resource + " with id " + id + " does not exist");
    }
}
