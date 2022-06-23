package io.mongock.runner.core.changelogs.withConstructor;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

@ChangeUnit(id = "ChangeUnitWithoutValidConstructor", order = "1")
public class ChangeUnitWithoutValidConstructor {

  private ChangeUnitWithoutValidConstructor() {
  }

  @Execution
  public void execution() {
  }

  @RollbackExecution
  public void rollbackExecution() {
  }
}
