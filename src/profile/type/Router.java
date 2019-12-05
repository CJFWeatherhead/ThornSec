/*
 * This code is part of the ThornSec project.
 *
 * To learn more, please head to its GitHub repo: @privacyint
 *
 * Pull requests encouraged.
 */
package profile.type;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;

import javax.json.stream.JsonParsingException;

import core.data.machine.AMachineData.MachineType;
import core.data.machine.configuration.NetworkInterfaceData;
import core.data.machine.configuration.NetworkInterfaceData.Direction;
import core.exception.AThornSecException;
import core.iface.IUnit;
import core.model.machine.ServerModel;
import core.model.machine.configuration.networking.BondInterfaceModel;
import core.model.machine.configuration.networking.BondModel;
import core.model.machine.configuration.networking.DHCPClientInterfaceModel;
import core.model.machine.configuration.networking.ISystemdNetworkd;
import core.model.machine.configuration.networking.MACVLANModel;
import core.model.machine.configuration.networking.MACVLANTrunkModel;
import core.model.machine.configuration.networking.NetworkInterfaceModel;
import core.model.machine.configuration.networking.StaticInterfaceModel;
import core.model.network.NetworkModel;
import core.profile.AStructuredProfile;
import core.unit.SimpleUnit;
import core.unit.fs.FilePermsUnit;
import core.unit.fs.FileUnit;
import core.unit.pkg.EnabledServiceUnit;
import core.unit.pkg.InstalledUnit;
import profile.dhcp.ADHCPServerProfile;
import profile.dhcp.ISCDHCPServer;
import profile.dns.ADNSServerProfile;
import profile.dns.UnboundDNSServer;

/**
 * This is a Router.
 *
 * This is where much of the security is enforced across the network. I've tried
 * to split it out as much as possible!
 *
 * If you want to make changes in here, you'll have a lot of reading to do :)!
 */
public class Router extends AStructuredProfile {
	private final ADNSServerProfile dnsServer;
	private final ADHCPServerProfile dhcpServer;

	public Router(String label, NetworkModel networkModel) throws AThornSecException, JsonParsingException {
		super(label, networkModel);

		final ServerModel me = getNetworkModel().getServerModel(getLabel());

		// Start by building our (bonded) trunk. This trunk will bond any LAN-facing
		// NICs, and we'll hang all our VLANs off it.
		final BondModel bond = new BondModel("LAN");
		me.addNetworkInterface(bond);
		try {
			for (final NetworkInterfaceData lanNic : networkModel.getData().getNetworkInterfaces(getLabel()).get(Direction.LAN)) {
				final NetworkInterfaceModel link = new BondInterfaceModel(lanNic.getIface(), bond);
				me.addNetworkInterface(link);
			}
		} catch (final IOException e) {
			e.printStackTrace();
		}

		// Now build the VLANs we'll be hanging all of our networking off
		final MACVLANTrunkModel trunk = new MACVLANTrunkModel("LAN");
		final MACVLANModel adminsVlan = new MACVLANModel(MachineType.ADMIN.toString(), trunk);
		final MACVLANModel usersVlan = new MACVLANModel(MachineType.USER.toString(), trunk);
		final MACVLANModel externalsVlan = new MACVLANModel(MachineType.EXTERNAL_ONLY.toString(), trunk);
		final MACVLANModel internalsVlan = new MACVLANModel(MachineType.INTERNAL_ONLY.toString(), trunk);
		final MACVLANModel serversVlan = new MACVLANModel(MachineType.SERVER.toString(), trunk);

		adminsVlan.setSubnet(getNetworkModel().getData().getAdminSubnet());
		adminsVlan.addAddress(getNetworkModel().getData().getAdminSubnet());

		usersVlan.setSubnet(getNetworkModel().getData().getUserSubnet());
		usersVlan.addAddress(getNetworkModel().getData().getUserSubnet());

		externalsVlan.setSubnet(getNetworkModel().getData().getExternalSubnet());
		externalsVlan.addAddress(getNetworkModel().getData().getExternalSubnet());

		internalsVlan.setSubnet(getNetworkModel().getData().getInternalSubnet());
		internalsVlan.addAddress(getNetworkModel().getData().getInternalSubnet());

		serversVlan.setSubnet(getNetworkModel().getData().getServerSubnet());
		serversVlan.addAddress(getNetworkModel().getData().getServerSubnet());

		me.addNetworkInterface(trunk);
		me.addNetworkInterface(adminsVlan);
		me.addNetworkInterface(usersVlan);
		me.addNetworkInterface(externalsVlan);
		me.addNetworkInterface(internalsVlan);
		me.addNetworkInterface(serversVlan);

		// if we want a guest network, build one of them, too
		if (getNetworkModel().getData().buildAutoGuest()) {
			final MACVLANModel guestsVlan = new MACVLANModel(MachineType.GUEST.toString(), trunk);
			guestsVlan.setSubnet(getNetworkModel().getData().getGuestSubnet());
			guestsVlan.addAddress(getNetworkModel().getData().getGuestSubnet());
			me.addNetworkInterface(guestsVlan);
		}

		// Finally, add any external network interfaces
		try {
			for (final NetworkInterfaceData wanNic : networkModel.getData().getNetworkInterfaces(getLabel()).get(Direction.WAN)) {

				NetworkInterfaceModel link = null;

				switch (wanNic.getInet()) {
				case STATIC:
					link = new StaticInterfaceModel(wanNic.getIface());
					link.addAddress(wanNic.getAddress());
					link.setGateway(wanNic.getGateway());
					link.setBroadcast(wanNic.getBroadcast());
					link.setIsIPMasquerading(true);
					break;
				case DHCP:
					link = new DHCPClientInterfaceModel(wanNic.getIface());
					link.setIsIPMasquerading(true);
					break;
				case PPP: // @TODO
					break;
				default:
				}
				me.addNetworkInterface(link);
			}

		} catch (final IOException e) {
			e.printStackTrace();
		}

		// Now create our DHCP Server.
		this.dhcpServer = new ISCDHCPServer(label, networkModel);
		this.dnsServer = new UnboundDNSServer(label, networkModel);
	}

	public final ISystemdNetworkd buildBond(String bondName) {
		return null;
	}

	public ADHCPServerProfile getDHCPServer() {
		return this.dhcpServer;
	}

	public ADNSServerProfile getDNSServer() {
		return this.dnsServer;
	}

	@Override
	protected Collection<IUnit> getInstalled() throws AThornSecException {
		final Collection<IUnit> units = new ArrayList<>();

		// Add useful tools for Routers here
		units.add(new InstalledUnit("traceroute", "proceed", "traceroute"));
		units.add(new InstalledUnit("speedtest_cli", "proceed", "speedtest-cli"));

		units.addAll(getDNSServer().getInstalled());
		units.addAll(getDHCPServer().getInstalled());

		return units;
	}

	@Override
	protected Collection<IUnit> getLiveConfig() throws AThornSecException {
		final Collection<IUnit> units = new ArrayList<>();

		units.addAll(getDHCPServer().getLiveConfig());
		units.addAll(getDNSServer().getLiveConfig());

		return units;
	}

	@Override
	public Collection<IUnit> getLiveFirewall() throws AThornSecException {
		final Collection<IUnit> units = new ArrayList<>();

		units.addAll(getDHCPServer().getLiveFirewall());
		units.addAll(getDNSServer().getLiveFirewall());

		return units;
	}

	@Override
	protected Collection<IUnit> getPersistentConfig() throws AThornSecException {
		final Collection<IUnit> units = new ArrayList<>();

		final FileUnit resolvConf = new FileUnit("leave_my_resolv_conf_alone", "proceed", "/etc/dhcp/dhclient-enter-hooks.d/leave_my_resolv_conf_alone");
		units.add(resolvConf);

		resolvConf.appendLine("make_resolv_conf() { :; }");

		units.add(new FilePermsUnit("leave_my_resolv_conf_alone", "leave_my_resolv_conf_alone", "/etc/dhcp/dhclient-enter-hooks.d/leave_my_resolv_conf_alone", "755",
				"I couldn't stop various systemd services deciding to override your DNS settings."
						+ " This will cause you intermittent, difficult to diagnose problems as it randomly"
						+ " sets your DNS to wherever it decides. Great for laptops/desktops, atrocious for servers..."));

		// The trunk device needs to be set to Promiscuous mode, or else the Kernel
		// can't see anything running over it.
		// As per
		// https://wiki.archlinux.org/index.php/Network_configuration#Promiscuous_mode
		final FileUnit promiscuousService = new FileUnit("promiscuous_service", "proceed", "/etc/systemd/system/promiscuous@.service",
				"I failed at creating a SystemD service to set the trunk (LAN) Network Interface card to promiscuous mode. Your Router will not be able to route any internal traffic.");
		units.add(promiscuousService);

		promiscuousService.appendLine("[Unit]");
		promiscuousService.appendLine("Description=Set %i interface in promiscuous mode");
		promiscuousService.appendLine("After=network.target");
		promiscuousService.appendCarriageReturn();
		promiscuousService.appendLine("[Service]");
		promiscuousService.appendLine("Type=oneshot");
		promiscuousService.appendLine("ExecStart=/usr/bin/ip link set dev %i promisc on");
		promiscuousService.appendLine("RemainAfterExit=yes");
		promiscuousService.appendCarriageReturn();
		promiscuousService.appendLine("[Install]");
		promiscuousService.appendLine("WantedBy=multi-user.target");

		units.add(new SimpleUnit("promiscuous_mode_enabled_lan", "promiscuous_service", "sudo systemctl enable promiscuous@LAN.service",
				"sudo systemctl is-enabled promiscuous@LAN.service", "enabled", "pass"));

		final FileUnit sysctl = new FileUnit("sysctl_conf", "proceed", "/etc/sysctl.conf");
		units.add(sysctl);

		sysctl.appendLine("net.ipv4.ip_forward=1");
		sysctl.appendLine("net.ipv6.conf.all.disable_ipv6=1");
		sysctl.appendLine("net.ipv6.conf.default.disable_ipv6=1");
		sysctl.appendLine("net.ipv6.conf.lo.disable_ipv6=1");
		// Stop our machine from spamming internal ARP requests on our external
		// interface
		// See
		// https://git.kernel.org/pub/scm/linux/kernel/git/davem/net.git/tree/Documentation/networking/ip-sysctl.txt#n1247
		sysctl.appendLine("net.ipv4.conf.all.arp_filter=1");
		sysctl.appendLine("net.ipv4.conf.default.arp_filter=1");
		// Set Reverse Path filtering to "strict"
		// See
		// https://git.kernel.org/pub/scm/linux/kernel/git/davem/net.git/tree/Documentation/networking/ip-sysctl.txt#n1226
		sysctl.appendLine("net.ipv4.conf.all.rp_filter=1");
		sysctl.appendLine("net.ipv4.conf.default.rp_filter=1");

		// Switch systemd-networkd on...
		units.add(new EnabledServiceUnit("systemd_networkd", "proceed", "systemd-networkd", "I was unable to enable the networking service. This is bad!"));

		units.addAll(getDHCPServer().getPersistentConfig());
		units.addAll(getDNSServer().getPersistentConfig());

		return units;
	}

	@Override
	public Collection<IUnit> getPersistentFirewall() throws AThornSecException {
		final Collection<IUnit> units = new ArrayList<>();

		units.addAll(getDHCPServer().getPersistentFirewall());
		units.addAll(getDNSServer().getPersistentFirewall());

		return units;
	}
}
