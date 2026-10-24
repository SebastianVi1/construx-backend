import org.andre.construx.modules.identity.service.AccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class Startup {
    /**
     * This method will be called when the application is ready to accept requests.
     * this is just for testing purposes
     */

    @Autowired
    AccountService accountService;


    private String gmail = "example@gmail.com";
    private String password = "password123.";


    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(){

        System.out.println("Server initialized and ready to accept requests.");
        createUser();


    }


    private void createUser(){




        accountService.register(gmail, password);
        System.out.println("User created with email: " + gmail + " and password: ");
    }




}
