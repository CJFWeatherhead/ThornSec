package profile;

import java.util.Iterator;
import java.util.Vector;

import core.iface.IUnit;
import core.model.NetworkModel;
import core.profile.AStructuredProfile;
import core.unit.fs.FileUnit;
import core.unit.pkg.InstalledUnit;
import core.unit.pkg.RunningUnit;

public class DHCP extends AStructuredProfile {

	private Vector<String> classes;
	private Vector<String> stanzas;
	
	public DHCP() {
		super("dhcp");

		classes = new Vector<String>();
		stanzas = new Vector<String>();
	}
	
	public void addStanza(String stanza) {
		stanzas.add(stanza);
	}
	
	public void addClass(String stanza) {
		classes.add(stanza);
	}

	public Vector<IUnit> getPersistentConfig(String server, NetworkModel model) {
		Vector<IUnit> units = new Vector<IUnit>();
		
		String defiface = "INTERFACES=\\\"";
		String procString = "/usr/sbin/dhcpd -4 -q -cf /etc/dhcp/dhcpd.conf ";

		//If our router is also a metal, then we only want to bind to bridges
		if (model.getServerModel(server).isMetal()) {
			units.addElement(new InstalledUnit("bridge_utils", "proceed", "bridge-utils"));

			//DHCP listening interfaces

			Vector<String> services = model.getServerModel(server).getServices();
			Iterator<String> service = services.iterator();
			
			while (service.hasNext()) {
				String subnet = model.getData().getSubnet(service.next());
				defiface += "br" + subnet + " ";
				procString += "br" + subnet + " ";
			}
		}
		//Otherwise, just bind to the internally-facing interface
		else {
			defiface += model.getData().getIface(server);
			procString += model.getData().getIface(server);
		}

		defiface = defiface.trim() + "\\\"";
		procString = procString.trim() + "$";
		
		units.addElement(new FileUnit("dhcp_defiface", "dhcp_installed", defiface, "/etc/default/isc-dhcp-server"));
		model.getServerModel(server).getProcessModel().addProcess(procString);
		
		String[] servers = model.getServerLabels();
		String dhcpconf  = "";
		
		for (int i = 0; i < servers.length; ++i) {
			if (!model.getServerModel(servers[i]).isRouter()) {
				dhcpconf = "\n\n";
				dhcpconf += "\tsubnet " + model.getServerModel(servers[i]).getSubnet() + " netmask " + model.getData().getNetmask() + " {\n";
				dhcpconf += "\t\thost " + servers[i] + " {\n";
				dhcpconf += "\t\t\thardware ethernet " + model.getServerModel(servers[i]).getMac() + ";\n";
				dhcpconf += "\t\t\tfixed-address " + model.getServerModel(servers[i]).getIP() + ";\n";
				dhcpconf += "\t\t\toption routers " + model.getServerModel(servers[i]).getGateway() + ";\n";
				dhcpconf += "\t\t}\n";
				dhcpconf += "\t}";
				
				stanzas.add(dhcpconf);
			}
		}
		
		String[] devices = model.getDeviceLabels();
		
		for (int i = 0; i < devices.length; ++i) {
			String[] subnets  = model.getDeviceModel(devices[i]).getSubnets();
			String[] ips      = model.getDeviceModel(devices[i]).getIPs();
			String[] macs     = model.getDeviceModel(devices[i]).getMacs();
			String[] gateways = model.getDeviceModel(devices[i]).getGateways();

			String netmask = model.getDeviceModel(devices[i]).getNetmask();
			
			for (int j = 0; j < subnets.length; ++j) {
				dhcpconf = "\n\n";
				dhcpconf += "\tsubnet " + subnets[j] + " netmask " + netmask + " {\n";
				dhcpconf += "\t\thost " + devices[i] + "_" + j + " {\n";
				dhcpconf += "\t\t\thardware ethernet " + macs[j] + ";\n";
				dhcpconf += "\t\t\tfixed-address " + ips[j] + ";\n";
				dhcpconf += "\t\t\toption routers " + gateways[j] + ";\n";
				dhcpconf += "\t\t}\n";
				dhcpconf += "\t}";
				
				stanzas.add(dhcpconf);
			}
		}
		
		return units;
	}

	protected Vector<IUnit> getInstalled(String server, NetworkModel model) {
		Vector<IUnit> units = new Vector<IUnit>();
		
		units.addElement(new InstalledUnit("dhcp", "isc-dhcp-server"));

		return units;
	}
	
	protected Vector<IUnit> getPersistentFirewall(String server, NetworkModel model) {
		Vector<IUnit> units = new Vector<IUnit>();
		
		units.addElement(model.getServerModel(server).getFirewallModel().addNatPostrouting("router_nat", "-j MASQUERADE"));

		units.addElement(model.getServerModel(server).getFirewallModel().addFilterInput("dhcp_ipt_in",
				"-i " + model.getData().getIface(server) + " -p udp --dport 67 -j ACCEPT"));
		units.addElement(model.getServerModel(server).getFirewallModel().addFilterOutput("dhcp_ipt_out",
				"-o " + model.getData().getIface(server) + " -p udp --sport 67 -j ACCEPT"));

		return units;
	}

	protected Vector<IUnit> getLiveConfig(String server, NetworkModel model) {
		Vector<IUnit> units = new Vector<IUnit>();
		
		units.addElement(new RunningUnit("dhcp", "isc-dhcp-server", "isc-dhcp-server"));
		
		String dhcpconf = "ddns-update-style none;\n";
		dhcpconf += "option domain-name \\\"" + model.getData().getDomain(server) + "\\\";\n";
		dhcpconf += "option domain-name-servers " + model.getServerModel(server).getGateway() + ";\n";
		dhcpconf += "default-lease-time 600;\n";
		dhcpconf += "max-lease-time 1800;\n";
		dhcpconf += "authoritative;\n";
		dhcpconf += "log-facility local7;";
		
		for (int i = 0; i < classes.size(); ++i) {
			dhcpconf += classes.elementAt(i);
		}

		dhcpconf += "\n\n";
		// add iptfwd for shared net
		dhcpconf += "shared-network sharednet {\n";
		dhcpconf += "\n";
		dhcpconf += "\tsubnet " + model.getServerModel(server).getSubnet() + " netmask " + model.getData().getNetmask() + " {\n";
		dhcpconf += "\t}";

		for (int i = 0; i < stanzas.size(); ++i) {
			dhcpconf += stanzas.elementAt(i);
		}
		
		dhcpconf += "}";

		units.addElement(new FileUnit("dhcp_conf", "dhcp_installed", dhcpconf, "/etc/dhcp/dhcpd.conf"));

		return units;
	}
	
}
