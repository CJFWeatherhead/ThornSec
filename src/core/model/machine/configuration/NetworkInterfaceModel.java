/*
 * This code is part of the ThornSec project.
 *
 * To learn more, please head to its GitHub repo: @privacyint
 *
 * Pull requests encouraged.
 */
package core.model.machine.configuration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;

import core.data.machine.AMachineData.MachineType;
import core.data.machine.configuration.NetworkInterfaceData.Inet;
import core.iface.IUnit;
import core.model.AModel;
import core.model.network.NetworkModel;
import core.unit.fs.FileUnit;
import inet.ipaddr.IPAddress;
import inet.ipaddr.mac.MACAddress;

/**
 * This model represents a Network Interface Card (NIC) attached to our network.
 *
 * Whilst originally based on the traditional "SysVinit" /etc/network/interfaces
 * file, with Debian's continued march towards its successor Systemd, we have
 * migrated to utilise systemd-networkd.
 *
 * This provides portability to other GNU/Linux distributions, but was not a
 * decision which was taken lightly. Systemd is a highly flawed technology,
 * which flies in the face of not just decades of practice, but also the UNIX
 * philosophy {@link https://en.wikipedia.org/wiki/Unix_philosophy} itself. Not
 * to mention that it's incredibly buggy.
 *
 * In theory, it's possible to have Systemd run DHCP servers on a given network
 * interface, however this functionality is _far_ from mature, and just isn't
 * good enough for our needs at this time.
 *
 * For more information, see https://wiki.debian.org/Debate/initsystem/sysvinit
 */
public class NetworkInterfaceModel extends AModel {
	private String comment;
	private String name;
	private Inet inet;

	private MACAddress mac;

	private Collection<String> macVLANS;

	private IPAddress subnet;
	private IPAddress address;
	private IPAddress netmask;
	private IPAddress broadcast;

	private IPAddress gateway;
	private Boolean ipForwarding;

	private Boolean ipMasquerading;

	public NetworkInterfaceModel(String label, NetworkModel networkModel) {
		super(label, networkModel);

		this.name = null;
		this.subnet = null;
		this.inet = null;
		this.macVLANS = null;
		this.address = null;
		this.netmask = null;
		this.broadcast = null;
		this.gateway = null;
		this.mac = null;
		this.comment = null;

		this.ipForwarding = false;
		this.ipMasquerading = false;
	}

	public static final Collection<IUnit> buildMACVLAN(MachineType vlanName, IPAddress subnet) {
		final Collection<IUnit> units = new ArrayList<>();

		final FileUnit netDev = new FileUnit(vlanName + "_netdev", "proceed", "/etc/systemd/network/20-" + vlanName + ".netdev");
		netDev.appendLine("[NetDev]");
		netDev.appendLine("Name=" + vlanName);
		netDev.appendLine("Kind=macvlan");
		netDev.appendCarriageReturn();
		netDev.appendLine("[MACVLAN]");
		netDev.appendLine("Mode=bridge");
		units.add(netDev);

		final FileUnit network = new FileUnit(vlanName + "_network", "proceed", "/etc/systemd/network/20-" + vlanName + ".network");
		network.appendLine("[Match]");
		network.appendLine("Name=" + vlanName);
		network.appendCarriageReturn();
		network.appendLine("[Network]");
		network.appendLine("Address=" + subnet.getLowerNonZeroHost());
		network.appendCarriageReturn();
		network.appendLine("[Route]");
		network.appendLine("GatewayOnLink=yes");
		network.appendCarriageReturn();
		network.appendLine("[RoutingPolicyRule]");
		network.appendLine("From=" + subnet.getLower());
		network.appendLine("To=" + subnet.getLower());
		units.add(network);

		return units;
	}

	public final void addMACVLAN(String name) {
		Collection<String> vlans = getMACVLANs();

		if (vlans == null) {
			vlans = new HashSet<>();
		}

		vlans.add(name);

		setMACVLANs(vlans);
	}

	public final IPAddress getAddress() {
		return this.address;
	}

	public final IPAddress getBroadcast() {
		return this.broadcast;
	}

	public final String getComment() {
		return this.comment;
	}

	public final IPAddress getGateway() {
		return this.gateway;
	}

	public final String getIface() {
		return this.name;
	}

	public final Inet getInet() {
		return this.inet;
	}

	public final Boolean getIsIPForwarding() {
		return this.ipForwarding;
	}

	public final Boolean getIsIPMasquerading() {
		return this.ipMasquerading;
	}

	public final MACAddress getMac() {
		return this.mac;
	}

	public final Collection<String> getMACVLANs() {
		return this.macVLANS;
	}

	public final IPAddress getNetmask() {
		return this.netmask;
	}

	/**
	 * Build a Systemd-networkd .network file for this NIC
	 *
	 * @return FileUnit in /etc/systemd/network/
	 */
	public FileUnit getNetworkFile() {
		String lex = null;

		switch (getInet()) {
		case MACVLAN:
			lex = "20-";
			break;
		default:
			lex = "00-";
			break;
		}

		final FileUnit network = new FileUnit(getIface() + "_network", "proceed", "/etc/systemd/network/" + lex + getIface() + ".network");
		network.appendLine("[Match]");
		network.appendLine("Name=" + getIface());
		network.appendCarriageReturn();

		//If this is a VLAN trunk, treat it a little differently
		if (getMACVLANs() != null) {
			network.appendLine("RequiredForOnline=yes");
			network.appendLine("ARP=no");
			network.appendCarriageReturn();
		}

		network.appendLine("[Network]");

		if (getIsIPForwarding()) {
			network.appendLine("IPForward=yes");
		}

		if (getIsIPMasquerading()) {
			network.appendLine("IPMasquerade=yes");
		}

		switch (getInet()) {
		case DHCP:
			network.appendLine("DHCP=yes");
			break;
		case MACVLAN:
		case STATIC:
			if (getAddress() != null) {
				network.appendLine("Address=" + getAddress().toCanonicalString());
			}
			if (getNetmask() != null) {
				network.appendLine("Netmask=" + getNetmask().toCanonicalString());
			}
			if (getBroadcast() != null) {
				network.appendLine("Broadcast=" + getBroadcast().toCanonicalString());
			}
			if (getGateway() != null) {
				network.appendLine("Gateway=" + getGateway().toCanonicalString());
			}
			break;
		default:
			break;
		}

		if (getMACVLANs() != null) {
			for (final String vlan : getMACVLANs()) {
				network.appendLine("MACVLAN=" + vlan);
			}
		}

		return network;
	}

	public final IPAddress getSubnet() {
		return this.subnet;
	}

	public final void setAddress(IPAddress address) {
		this.address = address;
	}

	public final void setBroadcast(IPAddress broadcast) {
		this.broadcast = broadcast;
	}

	public final void setComment(String comment) {
		this.comment = comment;
	}

	public final void setGateway(IPAddress gateway) {
		this.gateway = gateway;
	}

	public final void setIface(String iface) {
		this.name = iface;
	}

	public final void setInet(Inet inet) {
		this.inet = inet;
	}

	public final void setIsIPForwarding(Boolean value) {
		this.ipForwarding = value;
	}

	public final void setIsIPMasquerading(Boolean value) {
		this.ipMasquerading = value;
	}

	public final void setMac(MACAddress mac) {
		this.mac = mac;
	}

	public final void setMACVLANs(Collection<String> collection) {
		this.macVLANS = collection;
	}

	public final void setNetmask(IPAddress netmask) {
		this.netmask = netmask;
	}

	public final void setSubnet(IPAddress subnet) {
		this.subnet = subnet;
	 * physical, IPAddress localAddress, IPAddress remoteAddress) { final
	 * Collection<IUnit> units = new ArrayList<>();
	 *
	 * final FileUnit netDev = new FileUnit(vlanName + "_netdev", "proceed",
	 * "/etc/systemd/network/" + vlanName + ".netdev");
	 * netDev.appendLine("[NetDev]"); netDev.appendLine("Name=" + vlanName);
	 * netDev.appendLine("Kind=vxvlan"); netDev.appendCarriageReturn();
	 * netDev.appendLine("[VXVLAN]"); netDev.appendLine("Id=" +
	 * remoteAddress.toFullString().replaceAll("\\.", ""));
	 * netDev.appendLine("Remote=" + remoteAddress.toFullString());
	 * netDev.appendLine("Local=" + localAddress.toFullString()); units.add(netDev);
	 * 
	 * return units;
	 * 
	 * }
	 */
	
	
	}

//	private Hashtable<String, InterfaceData> interfaces;
//	private Set<String> customStanzas;
//
//	InterfaceModel(String label, NetworkModel networkModel) {
//		super(label, networkModel);
//
//		this.interfaces    = null;
//		this.customStanzas = null;
//	}
//
//	/**
//	 * Gets the configuration/audit units.
//	 *
//	 * @return the units
//	 */
//	public Vector<IUnit> getUnits() {
//		Vector<IUnit> units = new Vector<>();
//
//		//We need to handle network resets a bit more carefully (a little less #YOLO)
//
//
//		//If we're a router, we want to be very careful in our tearing down...
//		if (networkModel.getRouterServers().contains(me)) {
//			units.addElement(new SimpleUnit("net_conf_persist", "proceed",
//					"echo \"" + getPersistent() + "\" | sudo tee /etc/network/interfaces > /dev/null\n"
//					+ "sudo ip address flush lan0 & \n"
//					+ "ip addr show lan0 | grep -q '10.0.0.1' || (sudo ifdown lan0 &>/dev/null ; sudo ifup lan0 &>/dev/null ) &  \n"
//					+ "sudo service networking restart & \n",
//					"cat /etc/network/interfaces;", getPersistent(), "pass",
//					"Couldn't create our required network interfaces.  This will cause all sorts of issues."));
//		}
//		else {
//			units.addElement(new SimpleUnit("net_conf_persist", "proceed",
//					"echo \"" + getPersistent() + "\" | sudo tee /etc/network/interfaces > /dev/null\n"
//					+ "sudo service networking restart & \n",
//					"cat /etc/network/interfaces;", getPersistent(), "pass",
//					"Couldn't create our required network interfaces.  This will cause all sorts of issues."));
//		}
//
//		return units;
//	}
//
//    public void addIface(InterfaceData iface) {
//		if (this.interfaces == null) { this.interfaces = new Hashtable<String, InterfaceModel>(); }
//
//	}
//
//	public SimpleUnit addPPPIface(String name, String iface) {
//		String net = "";
//		net +=	"iface " + iface + " inet manual\n";
//		net += "\n";
//		net += "auto provider\n";
//		net += "iface provider inet ppp\n";
//		net += "provider provider";
//		customStanzas.add(net);
//		names.add(iface);
//		return new SimpleUnit(name, "proceed", "echo \\\"handled by model\\\";",
//				"grep \"iface provider inet ppp\" /etc/network/interfaces;",
//				"iface provider inet ppp", "pass");
//	}
//
//	private String getPersistent() {
//		String net = "source /etc/network/interfaces.d/*\n";
//		net += "\n";
//		net += "auto lo\n";
//		net += "iface lo inet loopback\n";
//		net += "pre-up /etc/ipsets/ipsets.up.sh | ipset -! restore\n";
//		net += "pre-up /etc/iptables/iptables.conf.sh | iptables-restore\n";
//		net += "\n";
//		net += "auto";
//		for (String name : names) {
//			net += " " + name;
//		}
//
//		for (String stanza : customStanzas) {
//			net += "\n\n";
//			net += stanza;
//		}
//
//		for (InterfaceData iface : ifaces) {
//			net += "\n\n";
//
//			//If we're a router, use the router declaration
//			if (networkModel.getRouterServers().contains(me) && iface.getIface().contains("lan")) {
//				net += iface.getRouterStanza();
//			}
//			//Otherwise, we're on the machine itself
//			else {
//				net += iface.getServerStanza();
//			}
//		}
//
//		return net.trim();
//	}

}
