/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.program.database.symbol;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import db.Record;
import ghidra.program.database.*;
import ghidra.program.database.external.ExternalLocationDB;
import ghidra.program.database.external.ExternalManagerDB;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.GlobalNamespace;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.CircularDependencyException;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.*;
import ghidra.program.util.ChangeManager;
import ghidra.util.Lock;
import ghidra.util.SystemUtilities;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.exception.InvalidInputException;
import ghidra.util.task.*;

/**
 * Base class for symbols
 */
public abstract class SymbolDB extends DatabaseObject implements Symbol {

	private Record record;
	private boolean isDeleting = false;
	protected Address address;
	protected SymbolManager symbolMgr;
	protected Lock lock;

	SymbolDB(SymbolManager symbolMgr, DBObjectCache<SymbolDB> cache, Address address,
			Record record) {
		super(cache, record.getKey());
		this.symbolMgr = symbolMgr;
		this.address = address;
		this.record = record;
		lock = symbolMgr.getLock();
	}

	SymbolDB(SymbolManager symbolMgr, DBObjectCache<SymbolDB> cache, Address address, long key) {
		super(cache, key);
		this.symbolMgr = symbolMgr;
		this.address = address;
		lock = symbolMgr.getLock();
	}

	@Override
	public String toString() {
		return getName();
	}

	@Override
	protected boolean refresh() {
		return refresh(null);
	}

	@Override
	protected boolean refresh(Record rec) {
		if (record != null) {
			if (rec == null) {
				rec = symbolMgr.getSymbolRecord(key);
			}
			if (rec == null ||
				record.getByteValue(SymbolDatabaseAdapter.SYMBOL_TYPE_COL) != rec.getByteValue(
					SymbolDatabaseAdapter.SYMBOL_TYPE_COL)) {
				return false;
			}
			record = rec;
			address = symbolMgr.getAddressMap().decodeAddress(
				rec.getLongValue(SymbolDatabaseAdapter.SYMBOL_ADDR_COL));
			return true;
		}
		return false;
	}

	@Override
	public Address getAddress() {
		lock.acquire();
		try {
			checkIsValid();
			return address;
		}
		finally {
			lock.release();
		}
	}

	protected void setAddress(Address addr) {
		if (!(this instanceof VariableSymbolDB)) {
			throw new IllegalArgumentException("Address setable for variables only");
		}
		ProgramDB program = symbolMgr.getProgram();
		record.setLongValue(SymbolDatabaseAdapter.SYMBOL_ADDR_COL,
			program.getAddressMap().getKey(addr, true));
		updateRecord();
		Address oldAddr = address;
		address = addr;
		program.symbolChanged(this, ChangeManager.DOCR_SYMBOL_ADDRESS_CHANGED, oldAddr, this,
			oldAddr, addr);
	}

	protected void move(Address oldBase, Address newBase) {
		lock.acquire();
		try {
			checkDeleted();
			// check if this symbol is in the address space that was moved.
			if (!oldBase.getAddressSpace().equals(address.getAddressSpace())) {
				return;
			}
			ProgramDB program = symbolMgr.getProgram();
			Address oldAddress = address;
			long diff = address.subtract(oldBase);
			address = newBase.addWrap(diff);
			record.setLongValue(SymbolDatabaseAdapter.SYMBOL_ADDR_COL,
				program.getAddressMap().getKey(address, true));
			updateRecord();
			symbolMgr.moveLabelHistory(oldAddress, address);
		}
		finally {
			lock.release();
		}
	}

	@Override
	public String getName() {
		lock.acquire();
		try {
			checkIsValid();
			if (record != null) {
				return record.getString(SymbolDatabaseAdapter.SYMBOL_NAME_COL);
			}

			return SymbolUtilities.getDynamicName(symbolMgr.getProgram(), address);
		}
		finally {
			lock.release();
		}
	}

	@Override
	public Program getProgram() {
		return symbolMgr.getProgram();
	}

	@Override
	public String getName(boolean includeNamespace) {
		lock.acquire();
		try {
			checkIsValid();
			String symName = getName();
			if (includeNamespace) {
				Namespace ns = getParentNamespace();
				if (!(ns instanceof GlobalNamespace)) {
					String nsPath = ns.getName(true);
					symName = nsPath + Namespace.NAMESPACE_DELIMITER + symName;
				}
			}
			return symName;
		}
		finally {
			lock.release();
		}
	}

	private void fillListWithNamespacePath(Namespace namespace, ArrayList<String> list) {
		Namespace parentNamespace = namespace.getParentNamespace();
		if (parentNamespace != null && parentNamespace.getID() != Namespace.GLOBAL_NAMESPACE_ID) {
			fillListWithNamespacePath(parentNamespace, list);
		}
		list.add(namespace.getName());
	}

	@Override
	public String[] getPath() {
		lock.acquire();
		try {
			checkIsValid();
			ArrayList<String> list = new ArrayList<>();
			fillListWithNamespacePath(getParentNamespace(), list);
			list.add(getName());
			String[] path = list.toArray(new String[list.size()]);
			return path;
		}
		finally {
			lock.release();
		}
	}

	@Override
	public int getReferenceCount() {
		lock.acquire();
		try {
			checkIsValid();
			ReferenceManager rm = symbolMgr.getReferenceManager();
			ReferenceIterator iter = rm.getReferencesTo(address);
			boolean isPrimary = this.isPrimary();
			Symbol[] symbols = symbolMgr.getSymbols(address);
			if (symbols.length == 1) {
				return rm.getReferenceCountTo(address);
			}
			int count = 0;
			while (iter.hasNext()) {
				Reference ref = iter.next();
				long symbolID = ref.getSymbolID();
				if (symbolID == key || (isPrimary && symbolID < 0)) {
					count++;
				}
			}
			return count;
		}
		finally {
			lock.release();
		}
	}

	@Override
	public Reference[] getReferences(TaskMonitor monitor) {
		lock.acquire();
		try {
			checkIsValid();
			if (monitor == null) {
				monitor = TaskMonitorAdapter.DUMMY_MONITOR;
			}

			if (monitor.getMaximum() == 0) {
				// If the monitor has not been initialized, then the progress will not correctly
				// display anything as setProgress() is called below.  We can't know what to
				// initialize to without counting all the references, which is as much work as
				// this method.
				monitor = new UnknownProgressWrappingTaskMonitor(monitor, 20);
			}

			ReferenceManager rm = symbolMgr.getReferenceManager();
			ReferenceIterator iter = rm.getReferencesTo(address);
			boolean isPrimary = this.isPrimary();
			ArrayList<Reference> list = new ArrayList<>();
			int cnt = 0;
			while (iter.hasNext()) {
				if (monitor.isCancelled()) {
					break; // return partial list
				}
				Reference ref = iter.next();
				long symbolID = ref.getSymbolID();
				if (symbolID == key || (isPrimary && symbolID < 0)) {
					list.add(ref);
					monitor.setProgress(cnt++);
				}
			}
			Reference[] refs = new Reference[list.size()];
			return list.toArray(refs);
		}
		finally {
			lock.release();
		}
	}

	@Override
	public Reference[] getReferences() {
		return getReferences(TaskMonitorAdapter.DUMMY_MONITOR);
	}

	@Override
	public boolean hasMultipleReferences() {
		lock.acquire();
		try {
			checkIsValid();
			ReferenceManager rm = symbolMgr.getReferenceManager();
			ReferenceIterator iter = rm.getReferencesTo(address);
			boolean isPrimary = this.isPrimary();
			int count = 0;
			while (iter.hasNext()) {
				Reference ref = iter.next();
				long symbolID = ref.getSymbolID();
				if (symbolID == key || (isPrimary && symbolID < 0)) {
					count++;
					if (count > 1) {
						return true;
					}
				}
			}
			return false;
		}
		finally {
			lock.release();
		}
	}

	@Override
	public boolean hasReferences() {
		lock.acquire();
		try {
			checkIsValid();
			ReferenceManager rm = symbolMgr.getReferenceManager();
			ReferenceIterator iter = rm.getReferencesTo(address);
			boolean isPrimary = this.isPrimary();
			while (iter.hasNext()) {
				Reference ref = iter.next();
				long symbolID = ref.getSymbolID();
				if (symbolID == key || (isPrimary && symbolID < 0)) {
					return true;
				}
			}
			return false;
		}
		finally {
			lock.release();
		}
	}

	@Override
	public boolean isDynamic() {
		return (record == null);
	}

	@Override
	public boolean isExternalEntryPoint() {
		lock.acquire();
		try {
			checkIsValid();
			return symbolMgr.isExternalEntryPoint(address);
		}
		finally {
			lock.release();
		}
	}

	@Override
	public abstract boolean isPrimary();

	/**
	 * Sets this symbol's source as specified.
	 * @param newSource the new source type (IMPORTED, ANALYSIS, USER_DEFINED)
	 * @throws IllegalArgumentException if you try to change the source from default or to default
	 */
	@Override
	public void setSource(SourceType newSource) {
		lock.acquire();
		try {
			checkDeleted();
			symbolMgr.validateSource(getName(), getAddress(), getSymbolType(), newSource);
			SourceType oldSource = getSource();
			if (newSource == oldSource) {
				return;
			}
			if (newSource == SourceType.DEFAULT || oldSource == SourceType.DEFAULT) {
				String msg = "Can't change between DEFAULT and non-default symbol. Symbol is " +
					getName() + " @ " + getAddress().toString() + ".";
				throw new IllegalArgumentException(msg);
			}
			if (record != null) {
				byte flags = record.getByteValue(SymbolDatabaseAdapter.SYMBOL_FLAGS_COL);
				byte clearBits = SymbolDatabaseAdapter.SYMBOL_SOURCE_BITS;
				byte setBits = (byte) newSource.ordinal();
				flags &= ~clearBits;
				flags |= setBits;
				record.setByteValue(SymbolDatabaseAdapter.SYMBOL_FLAGS_COL, flags);
				updateRecord();
				symbolMgr.symbolSourceChanged(this);
			}
		}
		finally {
			lock.release();
		}
	}

	@Override
	public SourceType getSource() {
		lock.acquire();
		try {
			checkIsValid();
			if (record == null) {
				return SourceType.DEFAULT;
			}
			byte sourceBits = SymbolDatabaseAdapter.SYMBOL_SOURCE_BITS;
			byte flags = record.getByteValue(SymbolDatabaseAdapter.SYMBOL_FLAGS_COL);
			byte adapterSource = (byte) (flags & sourceBits);
			return SourceType.values()[adapterSource];
		}
		finally {
			lock.release();
		}
	}

	@Override
	public boolean isPinned() {
		return false; //most symbols can't be pinned.
	}

	protected boolean doIsPinned() {
		lock.acquire();
		try {
			checkIsValid();
			if (record == null) {
				return false;
			}
			byte flags = record.getByteValue(SymbolDatabaseAdapter.SYMBOL_FLAGS_COL);
			return ((flags & SymbolDatabaseAdapter.SYMBOL_PINNED_FLAG) != 0);
		}
		finally {
			lock.release();
		}
	}

	@Override
	public void setPinned(boolean pinned) {
		throw new UnsupportedOperationException("Only Code Symbols may be pinned.");
	}

	protected void doSetPinned(boolean pinned) {
		lock.acquire();
		try {
			checkDeleted();
			if (pinned == isPinned()) {
				return;
			}
			if (record != null) {
				byte flags = record.getByteValue(SymbolDatabaseAdapter.SYMBOL_FLAGS_COL);
				if (pinned) {
					flags |= SymbolDatabaseAdapter.SYMBOL_PINNED_FLAG;
				}
				else {
					flags &= ~SymbolDatabaseAdapter.SYMBOL_PINNED_FLAG;
				}
				record.setByteValue(SymbolDatabaseAdapter.SYMBOL_FLAGS_COL, flags);
				updateRecord();
				symbolMgr.symbolAnchoredFlagChanged(this);
			}
		}
		finally {
			lock.release();
		}
	}

	@Override
	public void setName(String newName, SourceType source)
			throws DuplicateNameException, InvalidInputException {
		try {
			setNameAndNamespace(newName, getParentNamespace(), source);
		}
		catch (CircularDependencyException e) {
			// can't happen since we are only changing the name and not the namespace
		}
	}

	@Override
	public void setNamespace(Namespace newNamespace)
			throws DuplicateNameException, InvalidInputException, CircularDependencyException {

		setNameAndNamespace(getName(), newNamespace, getSource());
	}

	/**
	 * Allow symbol implementations to validate the source when setting the name of
	 * this symbol.
	 */
	protected SourceType validateNameSource(String newName, SourceType source) {
		return source;
	}

	@Override
	public void setNameAndNamespace(String newName, Namespace newNamespace, SourceType source)
			throws DuplicateNameException, InvalidInputException, CircularDependencyException {

		lock.acquire();
		try {
			checkDeleted();
			checkEditOK();

			source = validateNameSource(newName, source);

			symbolMgr.validateSource(newName, getAddress(), getSymbolType(), source);

			Namespace oldNamespace = getParentNamespace();
			boolean namespaceChange = !oldNamespace.equals(newNamespace);
			if (namespaceChange) {
				if (!isValidParent(newNamespace)) {
					throw new InvalidInputException("Namespace \"" + newNamespace.getName(true) +
						"\" is not valid for symbol " + getName());
				}
				if (isDescendant(newNamespace)) {
					throw new CircularDependencyException("Namespace \"" +
						newNamespace.getName(true) + "\" is a descendant of symbol " + getName());
				}
			}

			boolean nameChange = true;
			String oldName = getName();
			SourceType oldSource = getSource();
			if (source == SourceType.DEFAULT) {
				if (getSource() == SourceType.DEFAULT && !namespaceChange) {
					return;
				}
				newName = "";
			}
			else {
				SymbolUtilities.validateName(newName, address, getSymbolType(),
					symbolMgr.getAddressMap().getAddressFactory());
				nameChange = !oldName.equals(newName);
				if (!namespaceChange && !nameChange) {
					return;
				}
				symbolMgr.checkDuplicateSymbolName(address, newName, newNamespace, getSymbolType());
			}

			if (record != null) {

				List<SymbolDB> dynamicallyRenamedSymbols = getSymbolsDynamicallyRenamedByMyRename();
				List<String> oldDynamicallyRenamedSymbolNames = null;
				if (dynamicallyRenamedSymbols != null) {
					oldDynamicallyRenamedSymbolNames =
						new ArrayList<>(dynamicallyRenamedSymbols.size());
					for (Symbol s : dynamicallyRenamedSymbols) {
						oldDynamicallyRenamedSymbolNames.add(s.getName());
					}
				}

				record.setLongValue(SymbolDatabaseAdapter.SYMBOL_PARENT_COL, newNamespace.getID());
				record.setString(SymbolDatabaseAdapter.SYMBOL_NAME_COL, newName);
				updateSymbolSource(record, source);
				updateRecord();

				if (namespaceChange) {
					symbolMgr.symbolNamespaceChanged(this, oldNamespace);
				}
				if (nameChange) {
					SymbolType symbolType = getSymbolType();
					if (isExternal() &&
						(symbolType == SymbolType.FUNCTION || symbolType == SymbolType.CODE)) {
						ExternalManagerDB externalManager = symbolMgr.getExternalManager();
						ExternalLocationDB externalLocation =
							(ExternalLocationDB) externalManager.getExternalLocation(this);
						externalLocation.saveOriginalNameIfNeeded(oldNamespace, oldName, oldSource);
					}
					symbolMgr.symbolRenamed(this, oldName);
					if (dynamicallyRenamedSymbols != null) {
						ProgramDB program = symbolMgr.getProgram();
						for (int i = 0; i < dynamicallyRenamedSymbols.size(); i++) {
							Symbol s = dynamicallyRenamedSymbols.get(i);
							program.symbolChanged(s, ChangeManager.DOCR_SYMBOL_RENAMED,
								s.getAddress(), s, oldDynamicallyRenamedSymbolNames.get(i),
								s.getName());
						}
					}
				}
			}
			else {
				symbolMgr.convertDynamicSymbol(this, newName, newNamespace.getID(), source);
			}
		}
		finally {
			lock.release();
		}
	}

	protected List<SymbolDB> getSymbolsDynamicallyRenamedByMyRename() {
		return null;
	}

	private void checkEditOK() throws InvalidInputException {
		if (getSymbolType() == SymbolType.CODE) {
			for (Register reg : symbolMgr.getProgram().getRegisters(getAddress())) {
				if (reg.getName().equals(getName())) {
					throw new InvalidInputException("Register symbol may not be renamed");
				}
			}
		}
	}

	private void updateSymbolSource(Record symbolRecord, SourceType source) {
		byte flags = record.getByteValue(SymbolDatabaseAdapter.SYMBOL_FLAGS_COL);
		flags &= ~SymbolDatabaseAdapter.SYMBOL_SOURCE_BITS;
		flags |= (byte) source.ordinal();
		symbolRecord.setByteValue(SymbolDatabaseAdapter.SYMBOL_FLAGS_COL, flags);
	}

	/**
	 * @see ghidra.program.model.symbol.Symbol#setPrimary()
	 */
	@Override
	public boolean setPrimary() {
		return false;
	}

	@Override
	public long getID() {
		return key;
	}

	@Override
	public boolean equals(Object obj) {
		if ((obj == null) || (!(obj instanceof Symbol))) {
			return false;
		}
		if (obj == this) {
			return true;
		}
		Symbol s = (Symbol) obj;
		if (!getName().equals(s.getName())) {
			return false;
		}
		if (!getAddress().equals(s.getAddress())) {
			return false;
		}
		if (!getSymbolType().equals(s.getSymbolType())) {
			return false;
		}
		Symbol myParent = getParentSymbol();
		Symbol otherParent = s.getParentSymbol();

		return SystemUtilities.isEqual(myParent, otherParent);
	}

	@Override
	public int hashCode() {
		return (int) key;
	}

	private void updateRecord() {
		try {
			symbolMgr.getDatabaseAdapter().updateSymbolRecord(record);
		}
		catch (IOException e) {
			symbolMgr.dbError(e);
		}
	}

	@Override
	public Namespace getParentNamespace() {
		Symbol parent = getParentSymbol();
		if (parent != null) {
			return (Namespace) parent.getObject();
		}
		return symbolMgr.getProgram().getGlobalNamespace();
	}

	@Override
	public Symbol getParentSymbol() {
		lock.acquire();
		try {
			checkIsValid();
			if (record == null) {
				return null;
			}
			return symbolMgr.getSymbol(
				record.getLongValue(SymbolDatabaseAdapter.SYMBOL_PARENT_COL));
		}
		finally {
			lock.release();
		}
	}

	long getParentID() {
		lock.acquire();
		try {
			checkIsValid();
			if (record == null) {
				return Namespace.GLOBAL_NAMESPACE_ID;
			}
			return record.getLongValue(SymbolDatabaseAdapter.SYMBOL_PARENT_COL);
		}
		finally {
			lock.release();
		}
	}

	@Override
	public boolean isGlobal() {
		lock.acquire();
		try {
			checkIsValid();
			if (record == null) {
				return true;
			}
			return record.getLongValue(
				SymbolDatabaseAdapter.SYMBOL_PARENT_COL) == Namespace.GLOBAL_NAMESPACE_ID;
		}
		finally {
			lock.release();
		}
	}

	public String getSymbolData3() {
		lock.acquire();
		try {
			checkIsValid();
			if (record == null) {
				return null;
			}
			return record.getString(SymbolDatabaseAdapter.SYMBOL_DATA3_COL);
		}
		finally {
			lock.release();
		}
	}

	public void setSymbolData3(String data3) {
		lock.acquire();
		try {
			checkDeleted();
			if (record == null) {
				return;
			}
			String oldData = record.getString(SymbolDatabaseAdapter.SYMBOL_DATA3_COL);
			if (SystemUtilities.isEqual(data3, oldData)) {
				return;
			}
			record.setString(SymbolDatabaseAdapter.SYMBOL_DATA3_COL, data3);
			updateRecord();
			symbolMgr.symbolDataChanged(this);
		}
		finally {
			lock.release();
		}
	}

	protected void removeAllReferencesTo() {
		ReferenceManager refMgr = symbolMgr.getReferenceManager();
		ReferenceIterator it = refMgr.getReferencesTo(address);
		while (it.hasNext()) {
			Reference ref = it.next();
			refMgr.delete(ref);
		}
	}

	public long getSymbolData1() {
		lock.acquire();
		try {
			checkIsValid();
			if (record != null) {
				return record.getLongValue(SymbolDatabaseAdapter.SYMBOL_DATA1_COL);
			}
			return 0;
		}
		finally {
			lock.release();
		}
	}

	/**
	 * Sets the generic symbol data 1.
	 * @param value the value to set as symbol data 1.
	 */
	public void setSymbolData1(long value) {
		lock.acquire();
		try {
			checkDeleted();
			if (record != null) {
				record.setLongValue(SymbolDatabaseAdapter.SYMBOL_DATA1_COL, value);
				updateRecord();
				symbolMgr.symbolDataChanged(this);
			}
		}
		finally {
			lock.release();
		}
	}

	/**
	 * gets the generic symbol data 2 data.
	 */
	public int getSymbolData2() {
		lock.acquire();
		try {
			checkIsValid();
			if (record != null) {
				return record.getIntValue(SymbolDatabaseAdapter.SYMBOL_DATA2_COL);
			}
			return 0;
		}
		finally {
			lock.release();
		}
	}

	/**
	 * Sets the generic symbol data 2 data
	 * @param value the value to set as the symbols data 2 value.
	 */
	public void setSymbolData2(int value) {
		lock.acquire();
		try {
			checkDeleted();
			if (record != null) {
				record.setIntValue(SymbolDatabaseAdapter.SYMBOL_DATA2_COL, value);
				updateRecord();
				symbolMgr.symbolDataChanged(this);
			}
		}
		finally {
			lock.release();
		}
	}

	@Override
	public boolean delete() {
		lock.acquire();
		isDeleting = true;
		try {
			if (checkIsValid() && record != null) {
				return symbolMgr.doRemoveSymbol(this);
			}
		}
		finally {
			isDeleting = false;
			lock.release();
		}
		return false;
	}

	public boolean isDeleting() {
		return isDeleting;
	}

	@Override
	public boolean isDescendant(Namespace namespace) {
		if (this == namespace.getSymbol()) {
			return true;
		}
		Namespace parent = namespace.getParentNamespace();
		while (parent != null) {
			Symbol s = parent.getSymbol();
			if (this.equals(s)) {
				return true;
			}
			parent = parent.getParentNamespace();
		}

		return false;
	}

	@Override
	public abstract boolean isValidParent(Namespace parent);

	/**
	 * Change the record and key associated with this symbol
	 * @param the record.
	 */
	void setRecord(Record record) {
		this.record = record;
		keyChanged(record.getKey());
	}
}