package jc121f1;

import jc121f1.dagger.instance.DaggerInstanceWebServiceComponent;
import jc121f1.dagger.instance.InstanceWebServiceComponent;
import jc121f1.wbs.WebService;
import jc121f1.wbs.services.InstanceWebService;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    static void main(String[] args) {
        InstanceWebServiceComponent component = DaggerInstanceWebServiceComponent.create();
        WebService instanceService = new InstanceWebService(component);
        instanceService.start();
    }
}
