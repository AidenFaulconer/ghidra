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
package docking.widgets.table.constraint.provider;

import docking.widgets.table.constraint.ColumnConstraint;
import docking.widgets.table.constraint.ColumnData;
import docking.widgets.table.constrainteditor.*;

/**
 * Base class for providing single value numeric editors.
 *
 * @param <T> the number type of the column (Byte, Short, Integer, or Long)
 */
public class IntegerEditorProvider<T extends Number & Comparable<T>> implements EditorProvider<T> {
	protected LongConverter<T> converter;

	/**
	 * Constructor
	 * @param converter converts values of type T to long values for use by the editor.
	 */
	IntegerEditorProvider(LongConverter<T> converter) {
		this.converter = converter;
	}

	@Override
	public ColumnConstraintEditor<T> getEditor(ColumnConstraint<T> columnConstraint,
			ColumnData<T> columnDataSource) {
		return new IntegerConstraintEditor<>(columnConstraint, converter);
	}

	@Override
	public T parseValue(String value, Object dataSource) {
		long longValue = Long.parseLong(value);
		return converter.fromLong(longValue);
	}

	@Override
	public String toString(T value) {
		return value.toString();
	}
}
