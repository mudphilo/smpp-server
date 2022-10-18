package io.github.mudphilo.smppserver.packet;

/**
 * 
 * @author German Escobar
 */
public class Address {
	
	private byte ton;
	
    private byte npi;
    
    private String addressField;

	public byte getTon() {
		return ton;
	}
	
	public void setTon(byte ton) {
		this.ton = ton;
	}
	
	public Address withTon(byte ton) {
		setTon(ton);
		return this;
	}
	
	public byte getNpi() {
		return npi;
	}
	
	public void setNpi(byte npi) {
		this.npi = npi;
	}
	
	public Address withNpi(byte npi) {
		setNpi(npi);
		return this;
	}
	
	public String getAddressField() {
		return addressField;
	}
	
	public void setAddressField(String addressField) {
		this.addressField = addressField;
	}
	
	public Address withAddress(String address) {
		setAddressField(address);
		return this;
	}
	
}
